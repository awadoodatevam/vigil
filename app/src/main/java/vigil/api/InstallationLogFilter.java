package vigil.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Logs each use of an installation operation after its answer (AR-S-07): method, operation and
 * status, never the path, a header or a body.
 */
@Component
@Profile("!migrate")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class InstallationLogFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(InstallationLogFilter.class);

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ApiPaths.path(request).startsWith(ApiPaths.INSTALLATION + "/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } finally {
      LOG.info(
          "installation operation {} {}: {}",
          request.getMethod(),
          operation(request),
          response.getStatus());
    }
  }

  /** The pattern of the operation's handler, or {@code (no operation)} when none was mapped. */
  private static String operation(HttpServletRequest request) {
    if (request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
            instanceof HandlerMethod
        && request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
            instanceof String pattern) {
      return pattern;
    }
    return "(no operation)";
  }
}
