package vigil.identity.api;

import java.util.UUID;

/** The answer to a session token: {@code sessionId} and {@code user} are set only for ACTIVE. */
public record SessionCheck(Status status, UUID sessionId, UserView user) {

  /** The state of the session a token names. */
  public enum Status {
    ACTIVE,
    ENDED,
    UNKNOWN
  }
}
