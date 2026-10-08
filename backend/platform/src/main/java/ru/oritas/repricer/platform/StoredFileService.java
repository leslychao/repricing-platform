package ru.oritas.repricer.platform;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.model.CompletedPart;

/** Persisted evidence distinguishes transport EOF, committed object and published business data. */
@Service
public final class StoredFileService {
  private static final int PART_BYTES = 8 * 1024 * 1024;
  private final JdbcClient jdbc;
  private final ScopeExecutor scopes;
  private final ObjectStorage storage;
  private final Clock clock;

  public StoredFileService(
      JdbcClient jdbc, ScopeExecutor scopes, ObjectStorage storage, Clock clock) {
    this.jdbc = jdbc;
    this.scopes = scopes;
    this.storage = storage;
    this.clock = clock;
  }

  public FileRecord store(
      Scope scope,
      String kind,
      String mediaType,
      String originalName,
      InputStream source,
      long maximumBytes,
      Instant deadline)
      throws IOException {
    UUID id = prepare(scope, kind, mediaType, originalName);
    return storePrepared(scope, id, source, maximumBytes, deadline);
  }

  /** Allocate the raw identity and multipart before admitting the external request. */
  public UUID prepare(Scope scope, String kind, String mediaType, String originalName) {
    scope.requireOrganization();
    UUID id = UUID.randomUUID();
    String key = scope.organizationId() + "/" + scope.accountId() + "/" + id;
    scopes.execute(
        scope,
        Set.of("platform.file.manage"),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                      media_type,original_name,state,source_observed_at,expires_at)
                    VALUES (:id,:org,:account,:subject,:kind,:key,:media,:name,'UPLOADING',:observed,
                      clock_timestamp()+CASE :kind WHEN 'RAW' THEN interval '90 days'
                        WHEN 'IMPORT' THEN interval '7 days' WHEN 'REPORT' THEN interval '30 days'
                        ELSE interval '12 months' END)
                    """)
                .param("id", id)
                .param("org", scope.organizationId())
                .param("account", scope.accountId())
                .param("subject", scope.subjectId())
                .param("kind", kind)
                .param("key", key)
                .param("media", mediaType)
                .param("name", originalName)
                .param("observed", Timestamp.from(clock.instant()))
                .update());
    String uploadId = storage.initiate(key, mediaType);
    scopes.execute(
        scope,
        Set.of("platform.file.manage"),
        () ->
            jdbc.sql("UPDATE platform_file SET upload_id=:upload WHERE id=:id")
                .param("upload", uploadId)
                .param("id", id)
                .update());
    return id;
  }

  public FileRecord storePrepared(
      Scope scope, UUID id, InputStream source, long maximumBytes, Instant deadline)
      throws IOException {
    if (maximumBytes < 1
        || maximumBytes > 2L * 1024 * 1024 * 1024
        || deadline == null
        || !deadline.isAfter(clock.instant())
        || deadline.isAfter(clock.instant().plusSeconds(1800))) {
      throw new IllegalArgumentException("Invalid file size boundary");
    }
    Pending pending =
        scopes.execute(
            scope,
            Set.of("platform.file.manage"),
            () ->
                jdbc.sql(
                        """
                        UPDATE platform_file SET write_started_at=clock_timestamp(),write_deadline=:deadline
                        WHERE id=:id AND state='UPLOADING' AND NOT eof_confirmed
                          AND write_started_at IS NULL AND upload_id IS NOT NULL
                        RETURNING object_key,upload_id,media_type
                        """)
                    .param("id", id)
                    .param("deadline", Timestamp.from(deadline))
                    .query(
                        (row, index) ->
                            new Pending(row.getString(1), row.getString(2), row.getString(3)))
                    .optional()
                    .orElseThrow(
                        () ->
                            new BusinessException(
                                "UPLOAD_STATE_CONFLICT", 409, "Загрузка уже завершалась")));
    String key = pending.key();
    String uploadId = pending.uploadId();
    MessageDigest digest = sha256();
    long bytes = 0;
    boolean eof = false;
    try {
      byte[] part = new byte[PART_BYTES];
      int partNumber = 1;
      while (!eof) {
        int filled = 0;
        while (filled < part.length) {
          if (Thread.currentThread().isInterrupted() || clock.instant().isAfter(deadline)) {
            throw new IOException("File attempt interrupted or deadline reached");
          }
          int count = source.read(part, filled, part.length - filled);
          if (count == -1) {
            eof = true;
            break;
          }
          if (count == 0) {
            continue;
          }
          bytes = Math.addExact(bytes, count);
          if (bytes > maximumBytes) {
            throw new BusinessException("FILE_TOO_LARGE", 422, "Файл превышает допустимый размер");
          }
          digest.update(part, filled, count);
          filled += count;
        }
        if (filled > 0) {
          int currentPart = partNumber++;
          CompletedPart committed = storage.uploadPart(key, uploadId, currentPart, part, filled);
          scopes.execute(
              scope,
              Set.of("platform.file.manage"),
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO platform_file_part(file_id,organization_id,account_id,part_number,etag,checksum_crc32)
                          VALUES (:id,:org,:account,:number,:etag,:checksum)
                          """)
                      .param("id", id)
                      .param("org", scope.organizationId())
                      .param("account", scope.accountId())
                      .param("number", currentPart)
                      .param("etag", committed.eTag())
                      .param("checksum", committed.checksumCRC32())
                      .update());
        }
      }
      long finalBytes = bytes;
      String hash = HexFormat.of().formatHex(digest.digest());
      int recorded =
          scopes.execute(
              scope,
              Set.of("platform.file.manage"),
              () ->
                  jdbc.sql(
                          """
                          UPDATE platform_file SET byte_count=:bytes,sha256=:hash,eof_confirmed=true
                          WHERE id=:id AND state='UPLOADING' AND write_deadline>clock_timestamp()
                          """)
                      .param("bytes", finalBytes)
                      .param("hash", hash)
                      .param("id", id)
                      .update());
      if (recorded != 1) {
        throw new BusinessException("UPLOAD_LEASE_EXPIRED", 409, "Истёк срок работы с файлом");
      }
      String version;
      if (bytes == 0) {
        storage.abort(key, uploadId);
        version = storage.putEmpty(key, pending.mediaType());
      } else {
        version = storage.complete(key, uploadId, parts(scope, id));
      }
      if (version == null || version.equals("null")) {
        throw new IllegalStateException("S3 did not return an immutable object version");
      }
      var committed = storage.head(key, version);
      if (!version.equals(committed.versionId()) || committed.contentLength() != finalBytes) {
        throw new BusinessException(
            "UPLOAD_MISMATCH", 409, "Версия или длина загрузки не подтверждена");
      }
      scopes.execute(
          scope,
          Set.of("platform.file.manage"),
          () ->
              jdbc.sql(
                      """
                      UPDATE platform_file SET state='STORED',object_version=:version
                      WHERE id=:id AND eof_confirmed AND state='UPLOADING'
                      """)
                  .param("version", version)
                  .param("id", id)
                  .update());
      return scopes.execute(scope, Set.of("platform.file.manage"), () -> get(id));
    } catch (IOException | RuntimeException exception) {
      if (!eof) {
        try {
          storage.abort(key, uploadId);
          scopes.execute(
              scope,
              Set.of("platform.file.manage"),
              () ->
                  jdbc.sql(
                          "UPDATE platform_file SET state='FAILED' WHERE id=:id AND NOT"
                              + " eof_confirmed")
                      .param("id", id)
                      .update());
        } catch (RuntimeException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  /** A lost multipart-complete reply is reconciled against the original key and EOF digest. */
  public FileRecord reconcile(Scope scope, UUID id) throws IOException {
    FileRecord original = scopes.execute(scope, Set.of("platform.file.manage"), () -> get(id));
    if (Set.of("STORED", "READY").contains(original.state())) {
      return original;
    }
    if (!original.state().equals("UPLOADING")
        || !original.eofConfirmed()
        || original.sha256() == null) {
      throw new BusinessException("UPLOAD_INCOMPLETE", 409, "Завершение загрузки не подтверждено");
    }
    var head = storage.head(original.objectKey(), original.objectVersion());
    if (head.contentLength() != original.byteCount() || head.versionId() == null) {
      throw new BusinessException("UPLOAD_MISMATCH", 409, "Содержимое загрузки не подтверждено");
    }
    MessageDigest digest = sha256();
    try (InputStream input =
        new DigestInputStream(storage.read(original.objectKey(), head.versionId()), digest)) {
      byte[] buffer = new byte[64 * 1024];
      long total = 0;
      int count;
      while ((count = input.read(buffer)) != -1) {
        total += count;
        if (total > original.byteCount()) {
          throw new IOException("Object exceeds its original EOF evidence");
        }
      }
    }
    if (!original.sha256().equals(HexFormat.of().formatHex(digest.digest()))) {
      throw new BusinessException(
          "UPLOAD_MISMATCH", 409, "Контрольная сумма загрузки не совпадает");
    }
    scopes.execute(
        scope,
        Set.of("platform.file.manage"),
        () ->
            jdbc.sql(
                    """
                    UPDATE platform_file SET object_version=:version,state='STORED'
                    WHERE id=:id AND eof_confirmed AND state='UPLOADING'
                    """)
                .param("version", head.versionId())
                .param("id", id)
                .update());
    return scopes.execute(scope, Set.of("platform.file.manage"), () -> get(id));
  }

  /**
   * An incomplete multipart is abandoned only after its writer and maximum S3 call have expired.
   */
  public Recovery recover(Scope scope, UUID id) throws IOException {
    FileRecord file = scopes.execute(scope, Set.of("platform.file.manage"), () -> get(id));
    if (file.state().equals("STORED") || file.state().equals("READY")) {
      return new Recovery(file, null);
    }
    if (!file.state().equals("UPLOADING")) {
      return new Recovery(file, null);
    }
    if (file.eofConfirmed()) {
      return new Recovery(reconcile(scope, id), null);
    }
    UploadWork work =
        scopes.execute(
            scope,
            Set.of("platform.file.manage"),
            () ->
                jdbc.sql(
                        """
                        SELECT upload_id,write_started_at,COALESCE(write_deadline,created_at+interval '30 minutes')
                        FROM platform_file WHERE id=:id
                        """)
                    .param("id", id)
                    .query(
                        (row, index) ->
                            new UploadWork(
                                row.getString(1),
                                row.getTimestamp(2) != null,
                                row.getTimestamp(3).toInstant().plusSeconds(120)))
                    .single());
    if (!work.started() && work.uploadId() != null) {
      return new Recovery(file, null);
    }
    if (work.safeAfter().isAfter(clock.instant())) {
      return new Recovery(file, work.safeAfter());
    }
    boolean abandoned =
        scopes.execute(
            scope,
            Set.of("platform.file.manage"),
            () ->
                jdbc.sql(
                            """
                            UPDATE platform_file SET state='FAILED' WHERE id=:id AND state='UPLOADING' AND NOT eof_confirmed
                              AND COALESCE(write_deadline,created_at+interval '30 minutes')+interval '120 seconds'<=clock_timestamp()
                            """)
                        .param("id", id)
                        .update()
                    == 1);
    if (abandoned && work.uploadId() != null) {
      storage.abort(file.objectKey(), work.uploadId());
    }
    return new Recovery(scopes.execute(scope, Set.of("platform.file.manage"), () -> get(id)), null);
  }

  public FileRecord get(UUID id) {
    return jdbc.sql(
            """
            SELECT id,object_key,object_version,media_type,original_name,state,byte_count,sha256,eof_confirmed,
              organization_id,account_id,NULLIF(current_setting('app.subject_id',true),'')::uuid reader_id
            FROM platform_file WHERE id=:id
            """)
        .param("id", id)
        .query(
            (row, index) ->
                new FileRecord(
                    row.getObject("id", UUID.class),
                    row.getString("object_key"),
                    row.getString("object_version"),
                    row.getString("media_type"),
                    row.getString("original_name"),
                    row.getString("state"),
                    row.getLong("byte_count"),
                    row.getString("sha256"),
                    row.getBoolean("eof_confirmed"),
                    new Scope(
                        row.getObject("organization_id", UUID.class),
                        row.getObject("account_id", UUID.class),
                        row.getObject("reader_id", UUID.class))))
        .optional()
        .orElseThrow(() -> new BusinessException("FILE_NOT_FOUND", 404, "Файл недоступен"));
  }

  public InputStream open(FileRecord file) {
    if (!Set.of("STORED", "READY").contains(file.state())) {
      throw new BusinessException("FILE_NOT_READY", 409, "Файл ещё не готов");
    }
    UUID pin = UUID.randomUUID();
    Instant deadline = clock.instant().plusSeconds(1800);
    scopes.execute(
        file.scope(),
        Set.of("platform.file.manage"),
        () -> {
          lockReady(file.id());
          FileRecord current = get(file.id());
          if (!Objects.equals(current.objectVersion(), file.objectVersion())
              || !current.objectKey().equals(file.objectKey())) {
            throw new BusinessException("FILE_VERSION_CHANGED", 409, "Версия файла недоступна");
          }
          jdbc.sql(
                  """
                  INSERT INTO platform_file_read_pin(id,file_id,organization_id,account_id,lease_until)
                  VALUES (:pin,:file,:org,:account,:until)
                  """)
              .param("pin", pin)
              .param("file", file.id())
              .param("org", file.scope().organizationId())
              .param("account", file.scope().accountId())
              .param("until", Timestamp.from(deadline.plusSeconds(120)))
              .update();
          return true;
        });
    try {
      return new VerifiedInput(
          storage.read(file.objectKey(), file.objectVersion()),
          file,
          clock,
          deadline,
          () -> releaseRead(file.scope(), pin));
    } catch (RuntimeException failure) {
      try {
        releaseRead(file.scope(), pin);
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  public void retain(UUID fileId, String ownerType, UUID ownerId, Scope scope) {
    requireReferenceTransaction(ownerType, ownerId);
    lockReady(fileId);
    jdbc.sql(
            """
            INSERT INTO platform_file_reference(file_id,organization_id,account_id,owner_type,owner_id)
            VALUES (:file,:org,:account,:type,:owner) ON CONFLICT DO NOTHING
            """)
        .param("file", fileId)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("type", ownerType)
        .param("owner", ownerId)
        .update();
  }

  /** The business owner releases its exact reference in the same transaction as its lifecycle. */
  public void release(Scope scope, UUID fileId, String ownerType, UUID ownerId) {
    requireReferenceTransaction(ownerType, ownerId);
    jdbc.sql("SELECT id FROM platform_file WHERE id=:id FOR UPDATE")
        .param("id", fileId)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new BusinessException("FILE_NOT_FOUND", 404, "Файл недоступен"));
    jdbc.sql(
            """
            DELETE FROM platform_file_reference WHERE file_id=:file AND owner_type=:type
              AND owner_id=:owner AND organization_id=:org
              AND account_id IS NOT DISTINCT FROM :account
            """)
        .param("file", fileId)
        .param("type", ownerType)
        .param("owner", ownerId)
        .param("org", scope.requireOrganization())
        .param("account", scope.accountId())
        .update();
  }

  /** Releases a complete bounded owner set; an oversized set remains entirely retained. */
  public int releaseOwner(Scope scope, String ownerType, UUID ownerId, int maximumFiles) {
    requireReferenceTransaction(ownerType, ownerId);
    if (maximumFiles < 1 || maximumFiles > 500) {
      throw new IllegalArgumentException("Reference release limit must be between 1 and 500");
    }
    List<UUID> ids =
        jdbc.sql(
                """
                SELECT f.id FROM platform_file f JOIN platform_file_reference r ON r.file_id=f.id
                WHERE r.organization_id=:org AND r.account_id IS NOT DISTINCT FROM :account
                  AND r.owner_type=:type AND r.owner_id=:owner
                ORDER BY f.id LIMIT :limit FOR UPDATE OF f
                """)
            .param("org", scope.requireOrganization())
            .param("account", scope.accountId())
            .param("type", ownerType)
            .param("owner", ownerId)
            .param("limit", maximumFiles + 1)
            .query(UUID.class)
            .list();
    if (ids.size() > maximumFiles) {
      throw new BusinessException(
          "REFERENCE_RELEASE_LIMIT", 409, "Количество оснований превышает допустимую порцию");
    }
    if (ids.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            """
            DELETE FROM platform_file_reference WHERE file_id IN (:ids) AND owner_type=:type
              AND owner_id=:owner AND organization_id=:org
              AND account_id IS NOT DISTINCT FROM :account
            """)
        .param("ids", ids)
        .param("type", ownerType)
        .param("owner", ownerId)
        .param("org", scope.requireOrganization())
        .param("account", scope.accountId())
        .update();
  }

  private void lockReady(UUID id) {
    jdbc.sql("SELECT id FROM platform_file WHERE id=:id AND state IN ('STORED','READY') FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new BusinessException("FILE_NOT_READY", 409, "Файл недоступен"));
  }

  private void releaseRead(Scope scope, UUID pin) {
    scopes.execute(
        scope,
        Set.of("platform.file.manage"),
        () ->
            jdbc.sql("DELETE FROM platform_file_read_pin WHERE id=:pin")
                .param("pin", pin)
                .update());
  }

  private static void requireReferenceTransaction(String ownerType, UUID ownerId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("A file reference requires its owner's transaction");
    }
    if (ownerType == null || !ownerType.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || ownerId == null) {
      throw new IllegalArgumentException("Invalid file reference");
    }
  }

  private List<CompletedPart> parts(Scope scope, UUID id) {
    return scopes.execute(
        scope,
        Set.of("platform.file.manage"),
        () ->
            jdbc.sql(
                    """
                    SELECT part_number,etag,checksum_crc32 FROM platform_file_part WHERE file_id=:id ORDER BY part_number LIMIT 10000
                    """)
                .param("id", id)
                .query(
                    (row, index) ->
                        CompletedPart.builder()
                            .partNumber(row.getInt(1))
                            .eTag(row.getString(2))
                            .checksumCRC32(row.getString(3))
                            .build())
                .list());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public record FileRecord(
      UUID id,
      String objectKey,
      String objectVersion,
      String mediaType,
      String originalName,
      String state,
      long byteCount,
      String sha256,
      boolean eofConfirmed,
      Scope scope) {}

  private record Pending(String key, String uploadId, String mediaType) {}

  private record UploadWork(String uploadId, boolean started, Instant safeAfter) {}

  public record Recovery(FileRecord file, Instant retryAt) {}

  private static final class VerifiedInput extends InputStream {
    private final InputStream source;
    private final FileRecord file;
    private final Clock clock;
    private final Instant deadline;
    private final Runnable release;
    private final MessageDigest digest = sha256();
    private long count;
    private boolean verified;
    private boolean closed;

    private VerifiedInput(
        InputStream source, FileRecord file, Clock clock, Instant deadline, Runnable release) {
      this.source = source;
      this.file = file;
      this.clock = clock;
      this.deadline = deadline;
      this.release = release;
    }

    @Override
    public int read() throws IOException {
      requireActive();
      int value = source.read();
      if (value == -1) {
        verify();
      } else {
        digest.update((byte) value);
        count++;
        bound();
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      requireActive();
      int read = source.read(bytes, offset, length);
      if (read == -1) {
        verify();
      } else if (read > 0) {
        digest.update(bytes, offset, read);
        count += read;
        bound();
      }
      return read;
    }

    private void bound() throws IOException {
      if (count > file.byteCount()) {
        throw new IOException("Stored file exceeds its committed length");
      }
    }

    private void requireActive() throws IOException {
      if (closed || Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) {
        throw new IOException("Stored file read closed, interrupted or expired");
      }
    }

    private void verify() throws IOException {
      if (!verified) {
        if (count != file.byteCount()
            || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
          throw new IOException("Stored file does not match its committed digest");
        }
        verified = true;
      }
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        source.close();
      } catch (IOException failure) {
        try {
          release.run();
        } catch (RuntimeException releaseFailure) {
          failure.addSuppressed(releaseFailure);
        }
        throw failure;
      }
      try {
        release.run();
      } catch (RuntimeException failure) {
        throw new IOException("Could not release the stored file read lease", failure);
      }
    }
  }
}
