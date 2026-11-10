package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.knowledge.api.KnowledgePart.RunbookPart;

class RunbookFileTest {

  private static final Path DD_F_08 = Path.of("../docs/31-demo-data/08-runbooks.md");
  private static final String TABLE =
      "| Field | Value |\n| --- | --- |\n| runbook_key | RB-X-01 |\n| version | 1 |\n"
          + "| title | T |\n| applicable_alert_types | disk_full |\n"
          + "| applicable_component_kinds | (any) |\n";

  @Test
  @DisplayName("DD-F-08 08-runbooks.md gives 7 runbooks in file order with their fields and text")
  void demoFile() throws IOException {
    RunbookFile.Read read = RunbookFile.read(Files.readString(DD_F_08));
    assertThat(read.problems()).isEmpty();
    assertThat(read.runbooks())
        .extracting(RunbookPart::runbookKey)
        .containsExactly(
            "RB-API-01",
            "RB-DB-01",
            "RB-DB-02",
            "RB-RPT-01",
            "RB-SMS-01",
            "RB-SRV-01",
            "RB-USSD-01");
    RunbookPart db = read.runbooks().get(1);
    assertThat(db.version()).isEqualTo("1");
    assertThat(db.title()).isEqualTo("Subscriber DB server: /data filling up");
    assertThat(db.applicableAlertTypes())
        .containsExactly("disk_full", "disk_space_low", "db_write_errors");
    assertThat(db.applicableComponentKinds()).containsExactly("server", "database");
    assertThat(db.text()).startsWith("**When to use:**").endsWith("needs a change approval.");
    assertThat(read.runbooks().getFirst().applicableComponentKinds()).isEmpty();
  }

  @Test
  @DisplayName("DM-R-57 each problem of 08-runbooks.md with its text")
  void problems() {
    assertThat(RunbookFile.read("text without headings\n").problems())
        .containsExactly("08-runbooks.md: no runbook");
    assertThat(RunbookFile.read("## A\n\nNo table here.\n").problems())
        .containsExactly("08-runbooks.md 'A': no field table");
    assertThat(RunbookFile.read("## A\n\n| Field | Value |\ntext\n").problems())
        .containsExactly("08-runbooks.md 'A': no field table");
    assertThat(RunbookFile.read("## A\n\n").problems())
        .containsExactly("08-runbooks.md 'A': no field table");
    RunbookFile.Read bad = RunbookFile.read("## A\n" + TABLE + "| a | b | c |\n\ntext\n");
    assertThat(bad.problems())
        .containsExactly("08-runbooks.md 'A': table row 6 is not '| field | value |'");
    RunbookFile.Read unknown = RunbookFile.read("## A\n" + TABLE + "| owner | x |\ntext");
    assertThat(unknown.problems()).containsExactly("08-runbooks.md 'A': unknown field owner");
    assertThat(unknown.runbooks()).isEmpty();
    RunbookFile.Read twice = RunbookFile.read("## A\n" + TABLE + "| version | 2 |\ntext");
    assertThat(twice.problems())
        .containsExactly("08-runbooks.md 'A': field version appears more than once");
    assertThat(twice.runbooks()).isEmpty();
    RunbookFile.Read missing =
        RunbookFile.read("## A\n| Field | Value |\n| --- | --- |\n| runbook_key | K |\ntext");
    assertThat(missing.problems())
        .containsExactly(
            "08-runbooks.md 'A': missing field version",
            "08-runbooks.md 'A': missing field title",
            "08-runbooks.md 'A': missing field applicable_alert_types",
            "08-runbooks.md 'A': missing field applicable_component_kinds");
    RunbookFile.Read good = RunbookFile.read("intro\r\n## A \r\n" + TABLE + "\r\n  body  \r\n");
    assertThat(good.problems()).isEmpty();
    assertThat(good.runbooks())
        .containsExactly(
            new RunbookPart("RB-X-01", "1", "T", List.of("disk_full"), List.of(), "body"));
  }
}
