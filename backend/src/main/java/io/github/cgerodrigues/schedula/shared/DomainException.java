package io.github.cgerodrigues.schedula.shared;

public class DomainException extends RuntimeException {
  public DomainException(String message) {
    super(message);
  }

  public static void require(boolean condition, String message) {
    if (!condition) throw new DomainException(message);
  }
}
