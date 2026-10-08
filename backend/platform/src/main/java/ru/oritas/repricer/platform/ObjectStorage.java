package ru.oritas.repricer.platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

/** Versioned working objects. No backup, restore, public URL or unbounded SDK upload. */
@Component
public final class ObjectStorage implements DisposableBean {
  private final S3Client s3;
  private final String bucket;

  public ObjectStorage(
      @Value("${repricer.s3.endpoint}") URI endpoint,
      @Value("${repricer.s3.region}") String region,
      @Value("${repricer.s3.bucket}") String bucket,
      @Value("${repricer.s3.access-key}") String accessKey,
      @Value("${repricer.s3.secret-key}") String secretKey) {
    this.bucket = bucket;
    this.s3 =
        S3Client.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build())
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(10))
                    .socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(
                config ->
                    config
                        .apiCallTimeout(Duration.ofMinutes(2))
                        .apiCallAttemptTimeout(Duration.ofSeconds(60))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
            .build();
  }

  public void verifyVersioning() {
    try {
      s3.headBucket(request -> request.bucket(bucket));
    } catch (S3Exception exception) {
      if (exception.statusCode() != 404) {
        throw exception;
      }
      s3.createBucket(request -> request.bucket(bucket));
    }
    if (s3.getBucketVersioning(request -> request.bucket(bucket)).status()
        != BucketVersioningStatus.ENABLED) {
      s3.putBucketVersioning(
          request ->
              request
                  .bucket(bucket)
                  .versioningConfiguration(
                      VersioningConfiguration.builder()
                          .status(BucketVersioningStatus.ENABLED)
                          .build()));
    }
    if (s3.getBucketVersioning(request -> request.bucket(bucket)).status()
        != BucketVersioningStatus.ENABLED) {
      throw new IllegalStateException("Working object storage must support versioning");
    }
  }

  public String initiate(String key, String contentType) {
    return s3.createMultipartUpload(
            request ->
                request
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .checksumAlgorithm(ChecksumAlgorithm.CRC32))
        .uploadId();
  }

  public CompletedPart uploadPart(
      String key, String uploadId, int number, byte[] buffer, int length) {
    CRC32 crc = new CRC32();
    crc.update(buffer, 0, length);
    String checksum =
        Base64.getEncoder()
            .encodeToString(
                ByteBuffer.allocate(Integer.BYTES).putInt((int) crc.getValue()).array());
    try (InputStream input = new java.io.ByteArrayInputStream(buffer, 0, length)) {
      // Precomputed checksum avoids the provider's intermittent aws-chunked trailer rejection.
      var response =
          s3.uploadPart(
              request ->
                  request
                      .bucket(bucket)
                      .key(key)
                      .uploadId(uploadId)
                      .partNumber(number)
                      .contentLength((long) length)
                      .checksumAlgorithm(ChecksumAlgorithm.CRC32)
                      .checksumCRC32(checksum),
              RequestBody.fromInputStream(input, length));
      if (!checksum.equals(response.checksumCRC32())) {
        throw new IllegalStateException("S3 did not confirm the multipart checksum");
      }
      return CompletedPart.builder()
          .partNumber(number)
          .eTag(response.eTag())
          .checksumCRC32(checksum)
          .build();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot close in-memory part stream", exception);
    }
  }

  public String complete(String key, String uploadId, List<CompletedPart> parts) {
    return s3.completeMultipartUpload(
            request ->
                request
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()))
        .versionId();
  }

  public String putEmpty(String key, String mediaType) {
    return s3.putObject(
            request -> request.bucket(bucket).key(key).contentType(mediaType).contentLength(0L),
            RequestBody.empty())
        .versionId();
  }

  public HeadObjectResponse head(String key, String version) {
    return s3.headObject(request -> request.bucket(bucket).key(key).versionId(version));
  }

  public ResponseInputStream<GetObjectResponse> read(String key, String version) {
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("Reading requires an exact committed object version");
    }
    return s3.getObject(request -> request.bucket(bucket).key(key).versionId(version));
  }

  public void abort(String key, String uploadId) {
    try {
      s3.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId));
    } catch (S3Exception exception) {
      if (exception.statusCode() != 404) {
        throw exception;
      }
    }
  }

  public void delete(String key, String version) {
    s3.deleteObject(request -> request.bucket(bucket).key(key).versionId(version));
  }

  @Override
  public void destroy() {
    s3.close();
  }
}
