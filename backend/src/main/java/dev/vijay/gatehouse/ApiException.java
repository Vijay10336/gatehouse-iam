package dev.vijay.gatehouse;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

class ApiException extends RuntimeException {
  final HttpStatus status;

  ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }
}

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException error) {
    return ResponseEntity.status(error.status).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception error) {
    return ResponseEntity.status(409)
        .body(
            Map.of(
                "error",
                "That email, role, or client ID is already in use, or the record is still"
                    + " referenced."));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> invalid(Exception error) {
    return ResponseEntity.badRequest().body(Map.of("error", "Please check the entered values."));
  }

  @ExceptionHandler(org.springframework.mail.MailException.class)
  ResponseEntity<?> email(Exception error) {
    return ResponseEntity.status(503)
        .body(Map.of("error", "Email delivery is unavailable. Try again later."));
  }
}
