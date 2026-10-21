package vigil.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import vigil.connectors.api.Secrets;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/**
 * Checks the declared access of every vigil-api handler method before the handler, and so before
 * any module call of the operation (AR-I-07, PM-M23-02, PM-R-09).
 */
@Component
@Profile("!migrate")
class AccessInterceptor implements HandlerInterceptor {

  private static final String API_PACKAGE = AccessInterceptor.class.getPackageName();

  private static final String BEARER = "Bearer";

  private final IdentityService identity;
  private final Secrets secrets;

  AccessInterceptor(IdentityService identity, Secrets secrets) {
    this.identity = identity;
    this.secrets = secrets;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)
        || !API_PACKAGE.equals(method.getBeanType().getPackageName())) {
      return true;
    }
    if (method.hasMethodAnnotation(InstallationToken.class)) {
      checkInstallationToken(request);
      return true;
    }
    if (method.hasMethodAnnotation(Public.class)) {
      return true;
    }
    if (method.hasMethodAnnotation(LoggedIn.class)) {
      caller(request);
      return true;
    }
    RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
    if (required != null) {
      ApiCaller caller = caller(request);
      for (Permission permission : required.value()) {
        if (identity.hasPermission(caller.tenantId(), caller.user().id(), permission)) {
          return true;
        }
      }
    }
    throw new VigilError(Code.FORBIDDEN);
  }

  /**
   * AR-S-07: {@code Authorization} MUST be the scheme Bearer (any case), one space and a token
   * without spaces that matches the secret {@code installation-token}.
   */
  private void checkInstallationToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    int space = header == null ? -1 : header.indexOf(' ');
    if (space < 0
        || !header.substring(0, space).equalsIgnoreCase(BEARER)
        || space == header.length() - 1
        || header.indexOf(' ', space + 1) >= 0) {
      throw new VigilError(Code.NOT_SIGNED_IN);
    }
    if (!secrets.verifyInstallationToken(header.substring(space + 1))) {
      throw new VigilError(Code.BAD_TOKEN);
    }
  }

  /** The caller of the request; without one the 401 of the authentication entry point. */
  static ApiCaller caller(HttpServletRequest request) {
    if (request.getAttribute(SessionAuthenticationFilter.CALLER_ATTRIBUTE)
        instanceof ApiCaller caller) {
      return caller;
    }
    throw new VigilError(SecurityConfiguration.notAuthenticated(request));
  }
}
