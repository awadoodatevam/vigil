package vigil.api;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** The authentication of a request with an active session; it carries no authorities. */
final class ApiAuthentication extends AbstractAuthenticationToken {

  private final transient ApiCaller caller;

  ApiAuthentication(ApiCaller caller) {
    super(List.of());
    this.caller = caller;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public ApiCaller getPrincipal() {
    return caller;
  }
}
