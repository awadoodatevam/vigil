package vigil.connectors;

import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.TicketLine;

/** The Atlassian Document Format of ticket lines (AR-K-15): one paragraph per line, in order. */
final class Adf {

  private Adf() {}

  /** {@code {"type":"doc","version":1,"content":[…]}} with one paragraph per line. */
  static ObjectNode document(List<TicketLine> lines) {
    ObjectNode document = JsonBodies.JSON.createObjectNode();
    document.put("type", "doc");
    document.put("version", 1);
    ArrayNode content = document.putArray("content");
    for (TicketLine line : lines) {
      ObjectNode paragraph = content.addObject();
      paragraph.put("type", "paragraph");
      if (line.text().isEmpty()) {
        continue;
      }
      ArrayNode nodes = paragraph.putArray("content");
      if (line.link() == null) {
        nodes.addObject().put("type", "text").put("text", line.text());
        continue;
      }
      String before = line.text().substring(0, line.text().length() - line.link().length());
      if (!before.isEmpty()) {
        nodes.addObject().put("type", "text").put("text", before);
      }
      ObjectNode link = nodes.addObject().put("type", "text").put("text", line.link());
      link.putArray("marks")
          .addObject()
          .put("type", "link")
          .putObject("attrs")
          .put("href", line.link());
    }
    return document;
  }
}
