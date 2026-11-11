package vigil.connectors.api;

/**
 * One line of an outbound message (DC-OUT-00): {@code link} is {@code null}, or the address the
 * line ends with (AR-K-18).
 */
public record MessageLine(String text, String link) {

  public MessageLine {
    if (text == null || (link != null && (link.isEmpty() || !text.endsWith(link)))) {
      throw new IllegalArgumentException("message line: text ending with its link");
    }
  }

  /** A line without a link. */
  public static MessageLine of(String text) {
    return new MessageLine(text, null);
  }
}
