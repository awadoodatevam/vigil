package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.TicketLine;

class AdfTest {

  @Test
  @DisplayName("AR-K-15 one paragraph per line: plain, text and link, link only, empty; key order")
  void document() {
    String link = "https://demo.vigil.test/incidents/0190";
    String written =
        JsonBodies.JSON.writeValueAsString(
            Adf.document(
                List.of(
                    TicketLine.of("Incident: Disk full"),
                    new TicketLine("Open INC-0001 in Techfinity: " + link, link),
                    new TicketLine(link, link),
                    TicketLine.of(""))));
    String mark = "\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"" + link + "\"}}]";
    assertThat(written)
        .isEqualTo(
            "{\"type\":\"doc\",\"version\":1,\"content\":["
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"Incident: Disk full\"}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"Open INC-0001 in Techfinity: \"},"
                + "{\"type\":\"text\",\"text\":\""
                + link
                + "\","
                + mark
                + "}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
                + link
                + "\","
                + mark
                + "}]},"
                + "{\"type\":\"paragraph\"}]}");
    assertThat(JsonBodies.JSON.writeValueAsString(Adf.document(List.of())))
        .isEqualTo("{\"type\":\"doc\",\"version\":1,\"content\":[]}");
    assertThatThrownBy(() -> new TicketLine("no link here", link))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ticket line: text ending with its link");
    assertThatThrownBy(() -> new TicketLine("text", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
