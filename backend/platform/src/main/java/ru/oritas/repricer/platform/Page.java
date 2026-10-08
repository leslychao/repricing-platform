package ru.oritas.repricer.platform;

import java.util.List;

public record Page<T>(List<T> items, long total, int page, int size) {
  public Page {
    items = List.copyOf(items);
    if (total < 0 || page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Invalid page boundaries");
    }
  }

  public static int offset(int page, int size) {
    if (page < 0 || size < 1 || size > 200) {
      throw new BusinessException("INVALID_PAGE", 422, "Недопустимый размер страницы");
    }
    try {
      return Math.multiplyExact(page, size);
    } catch (ArithmeticException exception) {
      throw new BusinessException("INVALID_PAGE", 422, "Недопустимый номер страницы");
    }
  }
}
