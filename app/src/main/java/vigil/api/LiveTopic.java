package vigil.api;

import java.util.UUID;
import java.util.regex.Pattern;

/** A topic of the live update stream (AR-I-04): a kind, with an id for the kinds that take one. */
record LiveTopic(Kind kind, UUID id) {

  private static final Pattern ID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  /** The topics of AR-I-04 (complete list, in its order). */
  enum Kind {
    INCIDENTS("incidents", false),
    INCIDENT("incident", true),
    RUN("run", true),
    ALERTS("alerts", false),
    SOURCES("sources", false),
    APPROVAL("approval", true),
    WORKFLOW("workflow", false),
    TENANT("tenant", false),
    TEST_REPLAY("test-replay", true);

    private final String key;
    private final boolean takesId;

    Kind(String key, boolean takesId) {
      this.key = key;
      this.takesId = takesId;
    }

    String key() {
      return key;
    }

    boolean takesId() {
      return takesId;
    }
  }

  LiveTopic {
    if (kind == null) {
      throw new IllegalArgumentException("topic kind missing");
    }
    if (kind.takesId() != (id != null)) {
      throw new IllegalArgumentException(
          kind.takesId() ? "topic " + kind.key() + " needs an id" : "topic takes no id");
    }
  }

  /** The key, or {@code {key}:{id}} with the id in lower-case canonical form. */
  String text() {
    return kind.takesId() ? kind.key() + ":" + id : kind.key();
  }

  /** Accepts exactly the texts {@link #text()} can produce. */
  static LiveTopic parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("topic missing");
    }
    int colon = text.indexOf(':');
    String key = colon < 0 ? text : text.substring(0, colon);
    for (Kind kind : Kind.values()) {
      if (kind.key().equals(key)) {
        if (colon < 0) {
          return new LiveTopic(kind, null);
        }
        String id = text.substring(colon + 1);
        if (!ID.matcher(id).matches()) {
          throw new IllegalArgumentException("topic id is not a lower-case UUID");
        }
        return new LiveTopic(kind, UUID.fromString(id));
      }
    }
    throw new IllegalArgumentException("unknown topic");
  }
}
