package vigil.audit.api;

import java.util.UUID;

/** An actor filter: one user, or every module actor ("automatic", DC-S11-02). */
public record AuditActorFilter(Kind kind, UUID userId) {

  /** The kind of actor filter. */
  public enum Kind {
    USER,
    AUTOMATIC
  }

  public AuditActorFilter {
    if (kind == null) {
      throw new IllegalArgumentException("actor filter kind: required");
    }
    if (kind == Kind.USER && userId == null) {
      throw new IllegalArgumentException("actor filter userId: required for USER");
    }
  }
}
