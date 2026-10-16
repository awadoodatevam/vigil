package vigil.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vigil.identity.api.IdentityService;
import vigil.identity.api.NewSession;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/** Login, the current user and logout (AR-I-12 to AR-I-14). */
@RestController
@Profile("!migrate")
class SessionController {

  /** The one message of a refused login (DC-S01-03, DC-R-06). */
  static final String LOGIN_REFUSED = "Username or password is incorrect.";

  private final IdentityService identity;
  private final ApiCookies cookies;
  private final CookieCsrfTokenRepository csrfTokens;

  SessionController(
      IdentityService identity, ApiCookies cookies, CookieCsrfTokenRepository csrfTokens) {
    this.identity = identity;
    this.cookies = cookies;
    this.csrfTokens = csrfTokens;
  }

  /**
   * AR-I-12: the body is read as a JSON tree, so no value is coerced; a new session cookie and a
   * new CSRF token on success.
   */
  @PostMapping(path = "/api/v1/session", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Public
  User login(
      TenantId tenant,
      @RequestBody JsonNode body,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (!body.isObject() || !isString(body.get("username")) || !isString(body.get("password"))) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    NewSession session =
        identity
            .login(tenant, body.get("username").stringValue(), body.get("password").stringValue())
            .orElseThrow(() -> new VigilError(Code.NOT_SIGNED_IN, LOGIN_REFUSED));
    cookies.setSession(response, session.token());
    csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
    return User.of(session.user());
  }

  /** AR-I-13. */
  @GetMapping("/api/v1/session")
  @LoggedIn
  User current(ApiCaller caller) {
    return User.of(caller.user());
  }

  /** AR-I-14: ends the session and clears both cookies. */
  @DeleteMapping("/api/v1/session")
  @LoggedIn
  ResponseEntity<Void> logout(
      TenantId tenant, ApiCaller caller, HttpServletRequest request, HttpServletResponse response) {
    identity.logout(tenant, caller.sessionId());
    cookies.clearSession(response);
    csrfTokens.saveToken(null, request, response);
    return ResponseEntity.noContent().build();
  }

  private static boolean isString(JsonNode node) {
    return node != null && node.isString();
  }
}
