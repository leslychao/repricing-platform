package ru.oritas.repricer.platform;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backpressure connects a streaming writer to multipart storage; failed producers never emit EOF.
 */
public final class GeneratedFile {
  private GeneratedFile() {}

  public static StoredFileService.FileRecord store(
      StoredFileService files,
      Scope scope,
      String kind,
      String mediaType,
      String name,
      long maximumBytes,
      Instant deadline,
      Producer producer)
      throws IOException {
    return generate(
        producer,
        input -> files.store(scope, kind, mediaType, name, input, maximumBytes, deadline));
  }

  public static StoredFileService.FileRecord storePrepared(
      StoredFileService files,
      Scope scope,
      UUID id,
      long maximumBytes,
      Instant deadline,
      Producer producer)
      throws IOException {
    return generate(
        producer, input -> files.storePrepared(scope, id, input, maximumBytes, deadline));
  }

  private static StoredFileService.FileRecord generate(Producer producer, FileSink destination)
      throws IOException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (PipedInputStream pipe = new PipedInputStream(65536)) {
      PipedOutputStream output = new PipedOutputStream(pipe);
      Thread writer =
          Thread.ofVirtual()
              .name("file-writer")
              .start(
                  () -> {
                    try (output) {
                      try {
                        producer.write(
                            new FilterOutputStream(output) {
                              @Override
                              public void write(byte[] bytes, int offset, int length)
                                  throws IOException {
                                out.write(bytes, offset, length);
                              }

                              @Override
                              public void close() throws IOException {
                                flush();
                              }
                            });
                      } catch (Throwable exception) {
                        // Fatal producer failure must be visible before closing signals EOF.
                        failure.set(exception);
                      }
                    } catch (IOException exception) {
                      failure.compareAndSet(null, exception);
                    }
                  });
      InputStream guarded =
          new FilterInputStream(pipe) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              int size = super.read(bytes, offset, length);
              if (failure.get() != null) {
                throw new IOException("File producer did not complete", failure.get());
              }
              return size;
            }
          };
      try {
        return destination.store(guarded);
      } catch (IOException exception) {
        if (failure.get() instanceof BusinessException business) {
          throw business;
        }
        throw exception;
      } finally {
        writer.interrupt();
      }
    }
  }

  @FunctionalInterface
  public interface Producer {
    void write(OutputStream output) throws Exception;
  }

  @FunctionalInterface
  private interface FileSink {
    StoredFileService.FileRecord store(InputStream input) throws IOException;
  }
}
