package vigil.connectors.api;

/** The outcome of one message sent by a notification channel connector (AR-K-00d). */
public sealed interface Delivery permits Delivery.Sent, Delivery.Failed {

  /** The channel accepted the message. */
  record Sent() implements Delivery {}

  /** The message was not delivered, with the reason, cut to 500 characters. */
  record Failed(String reason) implements Delivery {

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
