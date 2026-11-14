package democontrol;

/**
 * A step of an operation that failed; the message is the error text of DC-S12-05 (spec 6.4 item 6).
 */
final class Failed extends Exception {

  Failed(String text) {
    super(text, null, false, false);
  }

  /** The error text of an exception: its message, or its class's simple name when it has none. */
  static String text(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }
}
