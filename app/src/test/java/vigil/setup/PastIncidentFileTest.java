package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PastIncidentFileTest {

  private static final Path DD_F_09 = Path.of("../docs/31-demo-data/09-past-incidents.csv");
  private static final String HEADER = String.join(",", PastIncidentFile.COLUMNS) + "\n";
  private static final String ROW = "PI-1,T,L,10,1,2,3,a,s,c,S,F,,0";

  @Test
  @DisplayName(
      "DD-F-09 09-past-incidents.csv gives 50 rows; quoted fields with commas, quotes and"
          + " line breaks")
  void demoFile() throws IOException {
    PastIncidentFile.Read read = PastIncidentFile.read(Files.readString(DD_F_09));
    assertThat(read.problems()).isEmpty();
    assertThat(read.rows()).hasSize(50);
    Map<String, String> first = read.rows().getFirst();
    assertThat(first.get("past_incident_key")).isEqualTo("PI-1001");
    assertThat(first.get("summary"))
        .isEqualTo(
            "Archive shipping caught up after a link outage and wrote two days of transaction log"
                + " archives back to /data. /data reached 100%, database writes failed, and"
                + " balance, bundle and SMS services failed with it.");
    assertThat(first.get("fix_description"))
        .isEqualTo(
            "Purged archived transaction logs older than 24 hours (oldest first, 8.8 GiB)."
                + " Services recovered within 3 minutes.");
    assertThat(first.get("fix_success_count")).isEqualTo("4");
    PastIncidentFile.Read quoted =
        PastIncidentFile.read(
            HEADER + "PI-1,\"say \"\"hi\"\"\",L,10,1,2,3,a,s,c,\"two\r\nlines\",F,,0\r\n");
    assertThat(quoted.problems()).isEmpty();
    assertThat(quoted.rows().getFirst().get("title")).isEqualTo("say \"hi\"");
    assertThat(quoted.rows().getFirst().get("summary")).isEqualTo("two\r\nlines");
  }

  @Test
  @DisplayName("DM-R-57 each problem of 09-past-incidents.csv with its text")
  void problems() {
    assertThat(PastIncidentFile.read("a,b\n" + ROW + "\n").problems())
        .containsExactly(
            "09-past-incidents.csv: the header must be "
                + String.join(",", PastIncidentFile.COLUMNS));
    assertThat(PastIncidentFile.read(HEADER + "a,b,c\n" + ROW + "\n").problems())
        .containsExactly("09-past-incidents.csv record 1: 3 fields, 14 expected");
    PastIncidentFile.Read after = PastIncidentFile.read(HEADER + "\"PI-1\"x,T\n" + ROW + "\n");
    assertThat(after.problems())
        .containsExactly("09-past-incidents.csv record 1: text after a closing quote");
    assertThat(after.rows()).hasSize(1);
    PastIncidentFile.Read open = PastIncidentFile.read(HEADER + ROW + "\n\"PI-2,T\n" + ROW + "\n");
    assertThat(open.problems())
        .containsExactly("09-past-incidents.csv record 2: a quoted field is not closed");
    assertThat(open.rows()).hasSize(1);
  }
}
