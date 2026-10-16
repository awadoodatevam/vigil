package vigil.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Answers every exception of a handler or interceptor in the format of AR-I-03. */
@RestControllerAdvice
@Profile("!migrate")
class ApiExceptionAdvice {

  @ExceptionHandler(Throwable.class)
  void handle(Throwable error, HttpServletResponse response) throws IOException {
    ApiErrors.answer(response, error);
  }
}
