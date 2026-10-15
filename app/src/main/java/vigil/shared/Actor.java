package vigil.shared;

import java.util.UUID;

/** Who caused a change: a named user or a module acting on its own (DM-V-01, DM-C-03). */
public record Actor(Kind kind, UUID userId, String module) {

  /** The kind of actor. */
  public enum Kind {
    USER,
    MODULE
  }

  private static final int MODULE_MAX_LENGTH = 40;

  public Actor {
    if (kind == null) {
      throw new IllegalArgumentException("actor kind missing");
    }
    if (kind == Kind.USER && (userId == null || module != null)) {
      throw new IllegalArgumentException("a user actor has a user id and no module");
    }
    if (kind == Kind.MODULE
        && (userId != null
            || module == null
            || module.isEmpty()
            || module.length() > MODULE_MAX_LENGTH)) {
      throw new IllegalArgumentException("a module actor has a module of 1 to 40 characters");
    }
  }

  public static Actor user(UUID userId) {
    return new Actor(Kind.USER, userId, null);
  }

  public static Actor module(String module) {
    return new Actor(Kind.MODULE, null, module);
  }
}
