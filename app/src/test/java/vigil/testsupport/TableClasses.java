package vigil.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/** The class table of AR-Q-06 and AR-Q-08: {@code db/table-classes.csv}. */
public final class TableClasses {

  /** The mutability classes of the table (DM-C-05 and the two technical classes). */
  public static final Set<String> CLASSES =
      Set.of(
          "immutable", "stateful", "configuration", "revisioned", "technical_rw", "technical_none");

  /** The 23 schemas of AR-P-04, in the order of AR Section 7.1. */
  public static final List<String> SCHEMAS =
      List.of(
          "audit",
          "tenancy",
          "identity",
          "connectors",
          "runner",
          "servicemap",
          "intake",
          "ai_gateway",
          "knowledge",
          "incidents",
          "investigation",
          "notifications",
          "ticketing",
          "routing",
          "correlation",
          "recovery",
          "diagnostics",
          "actions",
          "engine",
          "workflows",
          "reporting",
          "setup",
          "platform");

  /** One row: {@code table} is {@code schema.name}. */
  public record Row(String table, String mutabilityClass, boolean tenantScoped) {}

  private TableClasses() {}

  public static List<Row> rows() {
    List<String> lines;
    try {
      lines =
          new ClassPathResource("db/table-classes.csv")
              .getContentAsString(StandardCharsets.UTF_8)
              .lines()
              .filter(line -> !line.isBlank())
              .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!lines.getFirst().equals("table,class,tenant_scoped")) {
      throw new IllegalStateException("unexpected header of db/table-classes.csv");
    }
    return lines.stream().skip(1).map(TableClasses::parse).toList();
  }

  private static Row parse(String line) {
    String[] fields = line.split(",", -1);
    if (fields.length != 3
        || !CLASSES.contains(fields[1])
        || !(fields[2].equals("yes") || fields[2].equals("no"))) {
      throw new IllegalStateException("bad row in db/table-classes.csv: " + line);
    }
    return new Row(fields[0], fields[1], fields[2].equals("yes"));
  }
}
