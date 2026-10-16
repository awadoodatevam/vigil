package vigil.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import vigil.identity.api.IdentityService;
import vigil.identity.api.SessionCheck;
import vigil.shared.TenantId;

/**
 * Recognises the caller by the {@code vigil_session} cookie through vigil-identity (AR-T-11,
 * AR-S-02). A session that ended, is unknown or belongs to another tenant leaves the request
 * unauthenticated (SL-B-01).
 */
final class SessionAuthenticationFilter extends OncePerRequestFilter {

  static final String CALLER_ATTRIBUTE = SessionAuthenticationFilter.class.getName() + ".caller";

  private final IdentityService identity;
  private final SecurityContextHolderStrategy contexts =
      SecurityContextHolder.getContextHolderStrategy();

  SessionAuthenticationFilter(IdentityService identity) {
    this.identity = identity;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return ApiPaths.isTokenPath(request)
        || request.getAttribute(HostTenantFilter.TENANT_ATTRIBUTE) == null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<String> token = ApiCookies.sessionToken(request);
    if (token.isPresent()) {
      TenantId tenant = (TenantId) request.getAttribute(HostTenantFilter.TENANT_ATTRIBUTE);
      SessionCheck check;
      try {
        check = identity.authenticate(tenant, token.get());
      } catch (RuntimeException e) {
        ApiErrors.answer(response, e);
        return;
      }
      if (check.status() == SessionCheck.Status.ACTIVE) {
        ApiCaller caller = new ApiCaller(tenant, check.sessionId(), check.user());
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(new ApiAuthentication(caller));
        contexts.setContext(context);
      }
    }
    chain.doFilter(request, response);
  }
}
