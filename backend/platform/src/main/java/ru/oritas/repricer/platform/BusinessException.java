package ru.oritas.repricer.platform;

/** Safe, stable application error; never contains upstream response bodies or secrets. */
public final class BusinessException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;
  private final int status;

  public BusinessException(String code, int status, String message) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }
}
