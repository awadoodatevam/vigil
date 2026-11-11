package vigil.connectors.api;

/** The outcome of one ticketing connector request (AR-K-00c). */
public sealed interface TicketOutcome permits TicketOutcome.Done, TicketOutcome.Failed {

  /**
   * The request succeeded: the issue key, its address and its status name, each {@code null} where
   * the request gives none.
   */
  record Done(String key, String address, String status) implements TicketOutcome {}

  /** The request failed with the reason, cut to 500 characters. */
  record Failed(String reason) implements TicketOutcome {

    private static final int REASON_MAX = 500;

    public Failed {
      if (reason == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
      if (reason.codePointCount(0, reason.length()) > REASON_MAX) {
        reason = reason.substring(0, reason.offsetByCodePoints(0, REASON_MAX));
      }
    }
  }
}
