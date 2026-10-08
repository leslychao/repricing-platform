package ru.oritas.repricer.app;

import jakarta.validation.ConstraintViolationException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.oritas.repricer.platform.BusinessException;

@RestControllerAdvice
public final class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<Problem> business(BusinessException exception) {
    return ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(new Problem(exception.code(),
        exception.getMessage(), exception.status(), UUID.randomUUID()));
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
      ConstraintViolationException.class, IllegalArgumentException.class,
      java.time.DateTimeException.class,
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
      org.springframework.web.bind.MissingServletRequestParameterException.class})
  ResponseEntity<Problem> invalid(Exception exception) {
    return ResponseEntity.unprocessableContent().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(new Problem("INVALID_INPUT",
        "Проверьте формат и обязательные поля", 422, UUID.randomUUID()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Problem> conflict(DataIntegrityViolationException exception) {
    return ResponseEntity.status(409).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(new Problem("CONFLICT",
        "Данные изменились или нарушено ограничение операции", 409, UUID.randomUUID()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Problem> unexpected(Exception exception) {
    UUID id = UUID.randomUUID();
    log.error("Request {} failed: {}", id, exception.getClass().getSimpleName());
    return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(new Problem("INTERNAL_ERROR",
        "Операция не завершена. Код обращения: " + id, 500, id));
  }

  public record Problem(String code, String message, int status, UUID requestId) {}
}
