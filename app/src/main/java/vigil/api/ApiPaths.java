package vigil.api;

import jakarta.servlet.http.HttpServletRequest;

/** The path decisions of vigil-api, made on the servlet path (decoded and normalised). */
final class ApiPaths {

  static final String PUBLIC = "/api/v1/public";
  static final String SESSION = "/api/v1/session";
  static final String INSTALLATION = "/api/v1/installation";
  static final String WEBHOOKS = "/api/v1/webhooks";

  private ApiPaths() {}

  static String path(HttpServletRequest request) {
    String path = request.getServletPath();
    return path == null ? "" : path;
  }

  /** A path that starts with {@code /api/}. */
  static boolean isApi(HttpServletRequest request) {
    return path(request).startsWith("/api/");
  }

  /** {@code prefix} itself or a path below it ({@code prefix/**}). */
  static boolean under(HttpServletRequest request, String prefix) {
    String path = path(request);
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  /** The installation and webhook paths, which carry bearer tokens instead of a session. */
  static boolean isTokenPath(HttpServletRequest request) {
    return under(request, INSTALLATION) || under(request, WEBHOOKS);
  }

  static boolean isLogin(HttpServletRequest request) {
    return "POST".equals(request.getMethod()) && SESSION.equals(path(request));
  }
}
