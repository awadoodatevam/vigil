package vigil.investigation.api;

import java.util.Arrays;
import java.util.Optional;

/** The verdict of an AI investigation result on the hypothesis (DM-E-68). */
public enum Verdict {
  CONFIRMS("confirms"),
  DISPUTES("disputes");

  private final String key;

  Verdict(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<Verdict> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
