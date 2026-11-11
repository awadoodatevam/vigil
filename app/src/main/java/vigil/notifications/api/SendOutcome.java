package vigil.notifications.api;

/**
 * The outcome of a send request: {@code SENT} when at least one of its messages is delivered, now
 * or earlier (PM-M20-04), else {@code FAILED}.
 */
public enum SendOutcome {
  SENT,
  FAILED
}
