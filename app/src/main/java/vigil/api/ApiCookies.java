package vigil.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** The session cookie {@code vigil_session} (AR-S-02, BP-A-02). */
@Component
@Profile("!migrate")
class ApiCookies {

  static final String SESSION_COOKIE = "vigil_session";

  private final boolean secure;

  ApiCookies(@Value("${vigil.security.secure-cookies}") boolean secure) {
    this.secure = secure;
  }

  /** The value of the request's first {@code vigil_session} cookie, if it has one. */
  static Optional<String> sessionToken(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (SESSION_COOKIE.equals(cookie.getName())) {
          return Optional.of(cookie.getValue());
        }
      }
    }
    return Optional.empty();
  }

  void setSession(HttpServletResponse response, String token) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(token).build().toString());
  }

  void clearSession(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("").maxAge(0).build().toString());
  }

  private ResponseCookie.ResponseCookieBuilder cookie(String value) {
    return ResponseCookie.from(SESSION_COOKIE, value)
        .path("/")
        .httpOnly(true)
        .sameSite("Lax")
        .secure(secure);
  }
}
