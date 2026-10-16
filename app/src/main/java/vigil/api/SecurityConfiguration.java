package vigil.api;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import vigil.identity.api.IdentityService;
import vigil.shared.VigilError.Code;

/**
 * The Spring Security filter chain of vigil-api (AR-T-11): the session filter, CSRF (AR-S-03) and
 * the paths that need a session (SL-B-13).
 */
@Configuration
@Profile("!migrate")
@EnableWebSecurity
class SecurityConfiguration {

  static final String CSRF_COOKIE = "vigil_csrf";
  static final String CSRF_HEADER = "X-Vigil-CSRF";

  private static final Set<String> CSRF_METHODS = Set.of("POST", "PUT", "DELETE");

  @Bean
  CookieCsrfTokenRepository csrfTokenRepository(
      @Value("${vigil.security.secure-cookies}") boolean secureCookies) {
    CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
    repository.setCookieName(CSRF_COOKIE);
    repository.setHeaderName(CSRF_HEADER);
    repository.setCookiePath("/");
    repository.setCookieCustomizer(
        cookie -> cookie.httpOnly(false).sameSite("Strict").secure(secureCookies));
    return repository;
  }

  @Bean
  SecurityFilterChain apiFilterChain(
      HttpSecurity http, IdentityService identity, CookieCsrfTokenRepository csrfTokenRepository)
      throws Exception {
    http.securityMatcher(ApiPaths::isApi)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .headers(AbstractHttpConfigurer::disable)
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(new HeaderCsrfTokenRequestHandler())
                    .requireCsrfProtectionMatcher(SecurityConfiguration::requiresCsrf)
                    .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
        .addFilterBefore(new SessionAuthenticationFilter(identity), CsrfFilter.class)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(request -> ApiPaths.under(request, ApiPaths.PUBLIC))
                    .permitAll()
                    .requestMatchers(ApiPaths::isLogin)
                    .permitAll()
                    .requestMatchers(ApiPaths::isTokenPath)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, failure) ->
                            ApiErrors.write(response, ApiErrors.of(notAuthenticated(request))))
                    .accessDeniedHandler(
                        (request, response, denied) ->
                            ApiErrors.write(response, ApiErrors.of(Code.FORBIDDEN))));
    return http.build();
  }

  /**
   * Answers a request that the firewall refuses with 400 {@code bad_request}; on an error dispatch
   * (Tomcat's 405 for TRACE) it writes nothing (BP-A-20).
   */
  @Bean
  RequestRejectedHandler requestRejectedHandler() {
    return (request, response, rejected) -> {
      if (request.getDispatcherType() != DispatcherType.ERROR) {
        ApiErrors.write(response, ApiErrors.of(Code.BAD_REQUEST));
      }
    };
  }

  /** The 401 code: {@code session_ended} when the request carries a session cookie. */
  static Code notAuthenticated(HttpServletRequest request) {
    return ApiCookies.sessionToken(request).isPresent() ? Code.SESSION_ENDED : Code.NOT_SIGNED_IN;
  }

  /** POST, PUT and DELETE with an active session, except login, webhooks and installation. */
  static boolean requiresCsrf(HttpServletRequest request) {
    return CSRF_METHODS.contains(request.getMethod())
        && request.getAttribute(SessionAuthenticationFilter.CALLER_ATTRIBUTE) != null
        && !ApiPaths.isLogin(request)
        && !ApiPaths.isTokenPath(request);
  }

  /** The plain token, read only from the header {@code X-Vigil-CSRF} (AR-S-03). */
  static final class HeaderCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
      return request.getHeader(csrfToken.getHeaderName());
    }
  }
}
