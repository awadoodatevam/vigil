package vigil.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
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

  private final IdentityService identity;

  AccessInterceptor(IdentityService identity) {
    this.identity = identity;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)
        || !API_PACKAGE.equals(method.getBeanType().getPackageName())) {
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

  /** The caller of the request; without one the 401 of the authentication entry point. */
  static ApiCaller caller(HttpServletRequest request) {
    if (request.getAttribute(SessionAuthenticationFilter.CALLER_ATTRIBUTE)
        instanceof ApiCaller caller) {
      return caller;
    }
    throw new VigilError(SecurityConfiguration.notAuthenticated(request));
  }
}
