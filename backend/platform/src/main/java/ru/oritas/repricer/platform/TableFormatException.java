package ru.oritas.repricer.platform;

import java.io.IOException;

/** Invalid source bytes are a validation result, distinct from a retryable transport failure. */
public final class TableFormatException extends IOException {
  private static final long serialVersionUID = 1L;

  public TableFormatException(String message) {
    super(message);
  }

  public TableFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
