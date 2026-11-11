package vigil.connectors.api;

/**
 * One line of outbound ticket content (DC-OUT-00): {@code link} is {@code null}, or the address the
 * line ends with (AR-K-15).
 */
public record TicketLine(String text, String link) {

  public TicketLine {
    if (text == null || (link != null && (link.isEmpty() || !text.endsWith(link)))) {
      throw new IllegalArgumentException("ticket line: text ending with its link");
    }
  }

  /** A line without a link. */
  public static TicketLine of(String text) {
    return new TicketLine(text, null);
  }
}
