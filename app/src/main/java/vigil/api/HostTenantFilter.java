package vigil.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;

/**
 * Ties every {@code /api/} request except the installation paths to the tenant that the alias of
 * its host name points to (AR-I-02, BP-A-03); a host with no alias answers {@code unknown_host}.
 */
@Component
@Profile("!migrate")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class HostTenantFilter extends OncePerRequestFilter {

  static final String TENANT_ATTRIBUTE = HostTenantFilter.class.getName() + ".tenant";

  private final TenantService tenants;

  HostTenantFilter(TenantService tenants) {
    this.tenants = tenants;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ApiPaths.isApi(request) || ApiPaths.under(request, ApiPaths.INSTALLATION);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String hostName = hostName(request.getHeader("Host"));
    Optional<TenantId> tenant;
    if (hostName.isEmpty()) {
      tenant = Optional.empty();
    } else {
      try {
        tenant = tenants.resolveHost(hostName);
      } catch (RuntimeException e) {
        ApiErrors.answer(response, e);
        return;
      }
    }
    if (tenant.isEmpty()) {
      ApiErrors.write(response, ApiErrors.of(VigilError.Code.UNKNOWN_HOST));
      return;
    }
    request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
    chain.doFilter(request, response);
  }

  /** The {@code Host} header without its port, in lower case; empty when there is none. */
  static String hostName(String header) {
    if (header == null) {
      return "";
    }
    String name;
    if (header.startsWith("[")) {
      int end = header.indexOf(']');
      name = end < 0 ? header : header.substring(0, end + 1);
    } else {
      int colon = header.indexOf(':');
      name = colon < 0 ? header : header.substring(0, colon);
    }
    return name.toLowerCase(Locale.ROOT);
  }
}
