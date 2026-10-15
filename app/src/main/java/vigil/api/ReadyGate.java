package vigil.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers every request except the liveness probe with 503 {@code starting} until the restart
 * procedure has set the ready flag (AR-R-24).
 */
@Component
@Profile("!migrate")
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReadyGate extends OncePerRequestFilter {

  private static final String LIVENESS_PATH = "/actuator/health/liveness";
  private static final byte[] STARTING_BODY =
      "{\"error\":{\"code\":\"starting\"}}".getBytes(StandardCharsets.UTF_8);

  private final AtomicBoolean ready = new AtomicBoolean();

  void setReady() {
    ready.set(true);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (ready.get() || LIVENESS_PATH.equals(path)) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    response.setContentType("application/json");
    response.setContentLength(STARTING_BODY.length);
    response.getOutputStream().write(STARTING_BODY);
  }
}
