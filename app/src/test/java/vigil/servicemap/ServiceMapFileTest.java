package vigil.servicemap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.servicemap.ServiceMapFile.Component;
import vigil.servicemap.ServiceMapFile.Content;
import vigil.servicemap.ServiceMapFile.Identity;
import vigil.servicemap.ServiceMapFile.Service;
import vigil.servicemap.api.Counts;

class ServiceMapFileTest {

  static final Path DATA = Path.of("../docs/31-demo-data/02-service-map.yaml");

  /** The data file's text. */
  static String data() {
    try {
      return Files.readString(DATA, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The data file with {@code old} replaced once in line {@code n} (1-based). */
  static String change(int n, String old, String replacement) {
    List<String> lines = new ArrayList<>(List.of(data().split("\n", -1)));
    assertThat(lines.get(n - 1)).contains(old);
    lines.set(n - 1, lines.get(n - 1).replace(old, replacement));
    return String.join("\n", lines);
  }

  /** The data file with {@code added} as new lines after line {@code n}. */
  static String insertAfter(int n, String... added) {
    List<String> lines = new ArrayList<>(List.of(data().split("\n", -1)));
    lines.addAll(n, List.of(added));
    return String.join("\n", lines);
  }

  /** The data file with lines {@code from} to {@code to} replaced by {@code replacement}. */
  static String replaceLines(int from, int to, String... replacement) {
    List<String> lines = new ArrayList<>(List.of(data().split("\n", -1)));
    lines.subList(from - 1, to).clear();
    lines.addAll(from - 1, List.of(replacement));
    return String.join("\n", lines);
  }

  private static List<String> problems(String text) {
    return problems(text.getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> problems(byte[] bytes) {
    return ServiceMapFile.read(bytes).problems();
  }

  private static List<String> unreadable(String message) {
    return List.of("The file could not be read: " + message + ".");
  }

  private static Content content(String text) {
    ServiceMapFile.Read read = ServiceMapFile.read(text.getBytes(StandardCharsets.UTF_8));
    assertThat(read.problems()).isEmpty();
    return read.content();
  }

  private static <T> T entry(List<T> entries, java.util.function.Predicate<T> test) {
    return entries.stream().filter(test).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("DD-K-03 the data file reads with no problem: 4, 12, 15 and 6 entries")
  void dataFileReads() {
    Content file = content(data());
    assertThat(file.counts()).isEqualTo(new Counts(4, 12, 15, 6));
    assertThat(entry(file.components(), c -> c.componentKey().equals("srv-rpt-01")).ownerTeamKey())
        .isNull();
    assertThat(entry(file.services(), s -> s.serviceKey().equals("svc-mgmt-reports")).criticality())
        .isNull();
  }

  @Test
  @DisplayName("DM-R-54 every field of the four sections is read with its value")
  void fieldsAreRead() {
    Content file = content(data());
    assertThat(entry(file.services(), s -> s.serviceKey().equals("svc-balance-check")))
        .isEqualTo(
            new Service(
                "svc-balance-check",
                "Balance Check (USSD)",
                "critical",
                "vas-service-owners",
                List.of("ussd-gw", "api-balance", "db-subscriber")));
    assertThat(entry(file.components(), c -> c.componentKey().equals("srv-db-01")))
        .isEqualTo(
            new Component(
                "srv-db-01",
                "Subscriber DB server",
                "server",
                "critical",
                "db-ops",
                null,
                "lab-db-01"));
    assertThat(entry(file.components(), c -> c.componentKey().equals("ussd-probe")))
        .isEqualTo(
            new Component(
                "ussd-probe",
                "Synthetic USSD sessions",
                "check",
                "medium",
                "noc-l1",
                "srv-probe-01",
                null));
    assertThat(file.identities())
        .contains(new Identity("srv-db-01", "alertmanager", "instance", "lab-db-01:9100"));
    assertThat(file.dependencies().getFirst())
        .isEqualTo(new ServiceMapFile.Dependency("api-balance", "db-subscriber"));
  }

  @Test
  @DisplayName("AR-B-06 anchors, aliases, tags, an unknown section and a YAML error are refused")
  void fileFormat() {
    String anchored =
        change(47, "- {from_component_key: api-balance", "- &a {from_component_key: api-balance");
    assertThat(problems(anchored))
        .isEqualTo(unreadable("anchors, aliases and custom tags are not allowed (line 47)"));
    String alias =
        change(48, "- {from_component_key: api-bundle,  to_component_key: db-subscriber}", "- *a");
    assertThat(problems(alias))
        .isEqualTo(unreadable("anchors, aliases and custom tags are not allowed (line 48)"));
    String tagged = change(6, "name: \"Balance Check (USSD)\"", "name: !custom x");
    assertThat(problems(tagged))
        .isEqualTo(unreadable("anchors, aliases and custom tags are not allowed (line 6)"));
    String combined =
        anchored.replace(
            "- {from_component_key: api-bundle,  to_component_key: db-subscriber}", "- *a");
    assertThat(problems(combined))
        .isEqualTo(unreadable("anchors, aliases and custom tags are not allowed (line 47)"));
    assertThat(problems(combined.replace("name: \"Balance Check (USSD)\"", "name: !custom x")))
        .isEqualTo(unreadable("anchors, aliases and custom tags are not allowed (line 6)"));
    assertThat(problems(data() + "extra: []\n"))
        .isEqualTo(unreadable("unknown section 'extra' (line 53)"));
    assertThat(problems("a: ["))
        .isEqualTo(
            unreadable("expected the node content, but found '<stream end>' (line 1, column 5)"));
  }

  @Test
  @DisplayName("BP-A-24 each parser message, its line and the order of the checks")
  void parserMessages() {
    byte[] large = (data() + "#" + "x".repeat(1_048_577) + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] exact = new byte[1_048_577];
    System.arraycopy(large, 0, exact, 0, exact.length);
    assertThat(problems(exact)).isEqualTo(unreadable("the file is larger than 1 MiB"));
    assertThat(problems(new byte[] {(byte) 0xC3, (byte) 0x28}))
        .isEqualTo(unreadable("the file is not UTF-8"));
    assertThat(problems(change(6, "name: \"Balance Check (USSD)\"", "name: !!str x")))
        .isEqualTo(unreadable("anchors, aliases and custom tags are not allowed (line 6)"));
    String root =
        "the file must be a mapping of the sections services, components, identity_keys and"
            + " dependencies (line 1)";
    assertThat(problems("- a\n")).isEqualTo(unreadable(root));
    assertThat(problems("---\n" + data() + "---\n" + data())).isEqualTo(unreadable(root));
    assertThat(problems(data() + "[a]: x\n"))
        .isEqualTo(unreadable("a key must be a single word (line 53)"));
    assertThat(problems(data() + "services: []\n"))
        .isEqualTo(unreadable("duplicate key 'services' (line 53)"));
    assertThat(problems(replaceLines(46, 52)))
        .isEqualTo(unreadable("missing section 'dependencies'"));
    assertThat(problems(replaceLines(11, 25, "components: {}")))
        .isEqualTo(unreadable("section 'components' must be a list (line 11)"));
    assertThat(problems(replaceLines(6, 6, "  - x")))
        .isEqualTo(unreadable("services: each entry must be a mapping (line 6)"));
    assertThat(
            problems(
                change(
                    6,
                    "{service_key: svc-balance-check",
                    "{colour: red, service_key: svc-balance-check")))
        .isEqualTo(unreadable("services: unknown field 'colour' (line 6)"));
    assertThat(problems(change(9, "component_keys: [report-export]", "component_keys: x")))
        .isEqualTo(unreadable("services: component_keys must be a list (line 9)"));
    assertThat(problems(change(13, "name: \"Subscriber DB server\"", "name: [a]")))
        .isEqualTo(unreadable("components: field 'name' must be a single value (line 13)"));
    assertThat(problems(change(14, "kind: server, ", "")))
        .isEqualTo(unreadable("components: missing field 'kind' (line 14)"));

    List<String> first = new ArrayList<>();
    first.add("services: []");
    first.add("components: []");
    first.add("identity_keys: []");
    first.add("dependencies: []");
    while (first.size() < 39) {
      first.add("# line " + (first.size() + 1));
    }
    first.add("services: []");
    while (first.size() < 59) {
      first.add("# line " + (first.size() + 1));
    }
    first.add("[a]: x");
    assertThat(problems(String.join("\n", first) + "\n"))
        .isEqualTo(unreadable("a key must be a single word (line 60)"));

    List<String> entries = new ArrayList<>();
    entries.add("services:");
    entries.add("# line 2");
    entries.add("# line 3");
    entries.add("# line 4");
    entries.add("  - {service_key: s, component_keys: [c]}");
    entries.add("components:");
    while (entries.size() < 19) {
      entries.add("# line " + (entries.size() + 1));
    }
    entries.add("  - {component_key: c, name: C, kind: server, colour: red}");
    entries.add("identity_keys: []");
    entries.add("dependencies: []");
    assertThat(problems(String.join("\n", entries) + "\n"))
        .isEqualTo(unreadable("services: missing field 'name' (line 5)"));
  }

  @Test
  @DisplayName("DM-R-55 every import rule refuses the file with its text")
  void importRules() {
    assertImportRuleTexts();
  }

  @Test
  @DisplayName("DC-TX-11 every service map import problem text, exactly")
  void problemTexts() {
    assertImportRuleTexts();
  }

  private static void assertImportRuleTexts() {
    String srvDb01 = data().split("\n", -1)[12];
    assertThat(problems(insertAfter(13, srvDb01)))
        .containsExactly("components: key 'srv-db-01' appears more than once.");
    assertThat(problems(change(6, "[ussd-gw, api-balance, db-subscriber]", "[nope]")))
        .containsExactly("services 'svc-balance-check': component 'nope' is not in the file.");
    assertThat(problems(change(20, "runs_on_key: srv-api-01", "runs_on_key: db-subscriber")))
        .containsExactly("components 'api-balance': runs_on must name a server.");
    assertThat(
            problems(
                change(
                    13,
                    "target_key: lab-db-01}",
                    "target_key: lab-db-01, runs_on_key: srv-api-01}")))
        .containsExactly("components 'srv-db-01': a server cannot run on another component.");
    assertThat(
            problems(
                change(20, "runs_on_key: srv-api-01}", "runs_on_key: srv-api-01, target_key: x}")))
        .containsExactly("components 'api-balance': target_key is allowed only on a server.");
    assertThat(
            problems(
                insertAfter(
                    37,
                    "  - {component_key: db-subscriber, source_kind: zabbix, name: component,"
                        + " value: srv-db-01}")))
        .containsExactly(
            "identity_keys: zabbix component = 'srv-db-01' names more than one component.");
    assertThat(
            problems(
                insertAfter(
                    52,
                    "  - {from_component_key: db-subscriber, to_component_key: db-subscriber}")))
        .containsExactly("dependencies: 'db-subscriber' cannot depend on itself.");
    assertThat(
            problems(
                insertAfter(
                    52, "  - {from_component_key: api-balance, to_component_key: db-subscriber}")))
        .containsExactly("dependencies: 'api-balance' → 'db-subscriber' appears more than once.");
    assertThat(
            problems(
                insertAfter(
                    52, "  - {from_component_key: db-subscriber, to_component_key: api-balance}")))
        .containsExactly("dependencies: cycle api-balance → db-subscriber → api-balance.");
    assertThat(
            problems(
                insertAfter(
                    52, "  - {from_component_key: srv-db-01, to_component_key: ussd-probe}")))
        .containsExactly(
            "dependencies: cycle api-balance → db-subscriber → srv-db-01 → ussd-probe → ussd-gw"
                + " → api-balance.");
    assertThat(problems(change(9, "component_keys: [report-export]", "component_keys: []")))
        .containsExactly("services 'svc-mgmt-reports': needs at least one component.");
    assertThat(problems(change(13, "kind: server", "kind: router")))
        .containsExactly("components 'srv-db-01': 'router' is not a valid kind.");
    assertThat(problems(change(19, "runs_on_key: srv-db-01", "runs_on_key: nope")))
        .containsExactly("components 'db-subscriber': component 'nope' is not in the file.");
    String twice =
        change(
            6,
            "[ussd-gw, api-balance, db-subscriber]",
            "[api-balance, api-balance, db-subscriber, ussd-gw]");
    assertThat(problems(twice)).isEmpty();
    assertThat(
            ServiceMapGraph.of(1, content(twice))
                .service("svc-balance-check")
                .orElseThrow()
                .componentKeys())
        .containsExactly("api-balance", "db-subscriber", "ussd-gw");
    assertThat(problems(change(6, "criticality: critical", "criticality: urgent")))
        .containsExactly("services 'svc-balance-check': 'urgent' is not a valid criticality.");
    assertThat(problems(change(29, "source_kind: zabbix", "source_kind: nagios")))
        .containsExactly(
            "identity_keys 'nagios component = srv-db-01': 'nagios' is not a valid source_kind.");
    assertThat(problems(change(6, "service_key: svc-balance-check", "service_key: a b")))
        .containsExactly("services 'a b': 'a b' is not a valid service_key.");
    assertThat(
            problems(
                insertAfter(
                    52, "  - {from_component_key: api-balance, to_component_key: srv-api-01}")))
        .isEmpty();
    String three =
        insertAfter(13, srvDb01)
            .replace(
                "criticality: critical, owner_team_key: vas-service-owners, component_keys: [ussd-gw, api-balance",
                "criticality: urgent, owner_team_key: vas-service-owners, component_keys: [ussd-gw, api-balance")
            .replace("component_keys: [report-export]", "component_keys: []");
    assertThat(problems(three))
        .containsExactly(
            "services 'svc-balance-check': 'urgent' is not a valid criticality.",
            "components: key 'srv-db-01' appears more than once.",
            "services 'svc-mgmt-reports': needs at least one component.");
  }

  @Test
  @DisplayName("DM-E27-R1 one identity key names at most one component")
  void identityKeyNamesOneComponent() {
    assertThat(
            problems(
                insertAfter(
                    44,
                    "  - {component_key: srv-db-01, source_kind: alertmanager, name: component,"
                        + " value: x}",
                    "  - {component_key: db-subscriber, source_kind: alertmanager, name: component,"
                        + " value: x}")))
        .containsExactly(
            "identity_keys: alertmanager component = 'x' names more than one component.");
  }

  @Test
  @DisplayName("DM-E28-R1 a cycle through an implied runs_on dependency is refused")
  void cycleThroughRunsOn() {
    assertThat(
            problems(
                insertAfter(
                    52, "  - {from_component_key: srv-api-01, to_component_key: api-balance}")))
        .containsExactly("dependencies: cycle api-balance → srv-api-01 → api-balance.");
  }
}
