package vigil.notifications.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * The kinds of recipient (DM-E-77 {@code subject_kind}, DM-E-78 {@code recipient_kind}, complete
 * list).
 */
public enum RecipientKind {
  TEAM("team"),
  USER("user");

  private final String key;

  RecipientKind(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<RecipientKind> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
