package vigil.ticketing.api;

import java.util.Arrays;
import java.util.Optional;

/** The states of a ticket link (DM-E-75, DM-SM-14; complete list). */
public enum TicketLinkState {
  OPEN("open"),
  RESOLVED("resolved");

  private final String key;

  TicketLinkState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<TicketLinkState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
