package vigil.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.json.JsonMapper;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/**
 * Maps exceptions to the error answers of AR-I-03 (with BP-A-20) and writes them directly to the
 * response, without content negotiation.
 */
final class ApiErrors {

  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The default text of each code (complete list, BP-A-20); {@code starting} has none. */
  static final Map<Code, String> DEFAULT_MESSAGES = defaultMessages();

  private ApiErrors() {}

  /**
   * The answer to {@code error}, or empty when nothing is to be written (the client went away while
   * the answer was written).
   */
  static Optional<ApiError> map(Throwable error, boolean responseCommitted) {
    if (hasCause(
        error,
        cause ->
            cause instanceof DataAccessResourceFailureException
                || cause instanceof CannotCreateTransactionException)) {
      return Optional.of(of(Code.DEPENDENCY_FAILED));
    }
    if (error instanceof MaxUploadSizeExceededException) {
      return Optional.of(of(Code.TOO_LARGE));
    }
    if (error instanceof MultipartException) {
      return Optional.of(of(Code.BAD_REQUEST));
    }
    if (responseCommitted
        && hasCause(
            error,
            cause ->
                cause instanceof IOException
                    && !(cause instanceof HttpMessageNotReadableException))) {
      LOG.debug("client went away while the answer was written", error);
      return Optional.empty();
    }
    if (error instanceof VigilError vigilError) {
      return Optional.of(of(vigilError));
    }
    if (error instanceof NoResourceFoundException
        || error instanceof NoHandlerFoundException
        || error instanceof HttpRequestMethodNotSupportedException) {
      return Optional.of(of(Code.NOT_FOUND));
    }
    if (error instanceof HttpMessageNotReadableException
        || error instanceof TypeMismatchException) {
      return Optional.of(of(Code.BAD_REQUEST));
    }
    if (error instanceof ErrorResponse response && response.getStatusCode().value() == 413) {
      return Optional.of(of(Code.TOO_LARGE));
    }
    if (error instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
      return Optional.of(of(Code.BAD_REQUEST));
    }
    LOG.error("unexpected error", error);
    return Optional.of(of(Code.INTERNAL_ERROR));
  }

  /** The answer of {@code code} with its default text. */
  static ApiError of(Code code) {
    return of(new VigilError(code));
  }

  static ApiError of(VigilError error) {
    Code code = error.code();
    String message = error.message() != null ? error.message() : DEFAULT_MESSAGES.get(code);
    return new ApiError(code.status(), code.wireText(), message, error.details());
  }

  /** Maps {@code error} and writes its answer, if any. */
  static void answer(HttpServletResponse response, Throwable error) throws IOException {
    Optional<ApiError> answer = map(error, response.isCommitted());
    if (answer.isPresent()) {
      write(response, answer.get());
    }
  }

  /**
   * Writes {@code {"error":{"code","message","details"}}} in UTF-8 as {@code application/json};
   * {@code message} is left out when {@code null}, {@code details} when empty. Does nothing on a
   * committed response.
   */
  static void write(HttpServletResponse response, ApiError error) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("code", error.code());
    if (error.message() != null) {
      content.put("message", error.message());
    }
    if (error.details() != null && !error.details().isEmpty()) {
      content.put("details", error.details());
    }
    byte[] body = JSON.writeValueAsBytes(Map.of("error", content));
    response.resetBuffer();
    response.setStatus(error.status());
    response.setContentType("application/json");
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }

  private static boolean hasCause(Throwable error, Predicate<Throwable> test) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (test.test(cause)) {
        return true;
      }
    }
    return false;
  }

  private static Map<Code, String> defaultMessages() {
    Map<Code, String> messages = new EnumMap<>(Code.class);
    messages.put(Code.BAD_REQUEST, "The request is not valid.");
    messages.put(Code.SESSION_ENDED, "Your session ended. Sign in again.");
    messages.put(Code.NOT_SIGNED_IN, "You are not signed in.");
    messages.put(Code.BAD_TOKEN, "The token is not valid.");
    messages.put(Code.FORBIDDEN, "You do not have permission to do this.");
    messages.put(Code.NOT_FOUND, "Not found.");
    messages.put(Code.UNKNOWN_HOST, "This address is not set up.");
    messages.put(Code.CONFLICT, "This was changed meanwhile. Reload and try again.");
    messages.put(Code.TOO_LARGE, "The request is too large.");
    messages.put(Code.REFUSED, "The request was refused.");
    messages.put(Code.INVALID, "The request has problems.");
    messages.put(Code.INTERNAL_ERROR, "Something went wrong. Try again.");
    messages.put(Code.DEPENDENCY_FAILED, "A service is not available. Try again later.");
    return Collections.unmodifiableMap(messages);
  }
}
