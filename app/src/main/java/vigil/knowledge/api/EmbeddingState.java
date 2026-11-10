package vigil.knowledge.api;

import java.util.Arrays;
import java.util.Optional;

/** The embedding state of the knowledge settings (DM-E-67, DM-SM-15). */
public enum EmbeddingState {
  READY("ready"),
  REEMBEDDING("reembedding");

  private final String key;

  EmbeddingState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<EmbeddingState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
