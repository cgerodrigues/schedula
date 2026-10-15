package io.github.cgerodrigues.schedula.notification;

/** Sanitized classification only: never carries a remote body, credentials or cause. */
public final class IntegrationFailure extends RuntimeException {
  public enum Code {
    CONFIGURATION,
    INVALID_RESPONSE,
    NETWORK,
    HTTP_AUTH,
    HTTP_RATE_LIMIT,
    HTTP_SERVER,
    HTTP_CLIENT
  }

  private final Code code;
  private final boolean retryable;

  public IntegrationFailure(Code code, boolean retryable) {
    super(code.name());
    this.code = code;
    this.retryable = retryable;
  }

  public Code code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
