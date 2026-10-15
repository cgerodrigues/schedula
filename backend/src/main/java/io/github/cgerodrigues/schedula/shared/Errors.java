package io.github.cgerodrigues.schedula.shared;

import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> domain(DomainException e) {
    return error(409, e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return error(400, "Dados inválidos. Verifique os campos informados.");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> conflict(Exception e) {
    if (e.getMessage() != null && e.getMessage().contains("unit_normalized_name"))
      return error(409, "Já existe unidade com este nome no município.");
    return error(
        409, "Operação em conflito com um registro existente. Atualize e tente novamente.");
  }

  @ExceptionHandler(EmptyResultDataAccessException.class)
  ResponseEntity<ProblemDetail> missing(Exception e) {
    return error(404, "Registro não encontrado.");
  }

  private ResponseEntity<ProblemDetail> error(int status, String message) {
    return ResponseEntity.status(status)
        .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), message));
  }
}
