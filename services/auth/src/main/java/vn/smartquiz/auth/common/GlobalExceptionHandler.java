package vn.smartquiz.auth.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Map exceptions → {@code application/problem+json} (RFC 7807, design §15.1). */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<ProblemDetail> handleAuth(AuthException ex, HttpServletRequest req) {
    return build(ex.code(), ex.getMessage(), req, null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fe ->
                    Map.of(
                        "field",
                        fe.getField(),
                        "message",
                        fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
            .toList();
    return build(ErrorCode.AUTH_WEAK_PASSWORD, "Request không hợp lệ", req, errors);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest req) {
    log.error("Unhandled exception at {}", req.getRequestURI(), ex);
    return build(ErrorCode.AUTH_INTERNAL, ErrorCode.AUTH_INTERNAL.defaultTitle(), req, null);
  }

  private ResponseEntity<ProblemDetail> build(
      ErrorCode code, String title, HttpServletRequest req, List<Map<String, String>> errors) {
    HttpStatus status = code.status();
    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(URI.create(code.typeUri()));
    pd.setTitle(title);
    pd.setProperty("code", code.name());
    pd.setProperty("timestamp", Instant.now().toString());
    if (req != null) {
      pd.setInstance(URI.create(req.getRequestURI()));
    }
    if (errors != null && !errors.isEmpty()) {
      pd.setProperty("errors", errors);
    }
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
  }
}
