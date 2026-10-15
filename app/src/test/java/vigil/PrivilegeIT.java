package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.testsupport.TableClasses;
import vigil.testsupport.VigilDatabase;

class PrivilegeIT {

  /** The privileges of vigil_app per class (AR-P-06, DM-C-05). */
  private static final Map<String, Set<String>> EXPECTED =
      Map.of(
          "immutable", Set.of("SELECT", "INSERT"),
          "revisioned", Set.of("SELECT", "INSERT"),
          "stateful", Set.of("SELECT", "INSERT", "UPDATE"),
          "configuration", Set.of("SELECT", "INSERT", "UPDATE"),
          "technical_rw", Set.of("SELECT", "INSERT", "UPDATE"),
          "technical_none", Set.of());

  private static JdbcClient postgres;
  private static Set<String> tables;
  private static List<TableClasses.Row> rows;

  @BeforeAll
  static void read() {
    postgres = VigilDatabase.main().postgres();
    tables =
        new HashSet<>(
            postgres
                .sql(
                    "SELECT table_schema || '.' || table_name FROM information_schema.tables"
                        + " WHERE table_type = 'BASE TABLE' AND table_schema = ANY (?)")
                .param(TableClasses.SCHEMAS.toArray(String[]::new))
                .query(String.class)
                .list());
    rows = TableClasses.rows();
  }

  @Test
  @DisplayName("AR-Q-06 every table has a row in the class table and every row names a table")
  void classTableMatchesTheDatabase() {
    assertThat(tables).isNotEmpty();
    assertThat(rows)
        .extracting(TableClasses.Row::table)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(tables);
  }

  @Test
  @DisplayName("AR-P-06 vigil_app has exactly the privileges of its table's class")
  void privilegesMatchTheClass() {
    Map<String, Set<String>> granted = grantedToApp();
    for (TableClasses.Row row : rows) {
      assertThat(granted.getOrDefault(row.table(), Set.of()))
          .as("privileges of vigil_app on %s (%s)", row.table(), row.mutabilityClass())
          .containsExactlyInAnyOrderElementsOf(EXPECTED.get(row.mutabilityClass()));
    }
  }

  @Test
  @DisplayName("DM-C-05 the mutability classes drive the grants")
  void mutabilityClassesDriveTheGrants() {
    assertThat(EXPECTED.keySet()).isEqualTo(TableClasses.CLASSES);
    Map<String, Set<String>> granted = grantedToApp();
    for (TableClasses.Row row : rows) {
      assertThat(granted.getOrDefault(row.table(), Set.of()))
          .as("privileges of vigil_app on %s", row.table())
          .isEqualTo(EXPECTED.get(row.mutabilityClass()));
    }
  }

  @Test
  @DisplayName("DM-C-04 no table grants DELETE or TRUNCATE to vigil_app")
  void noDeleteOrTruncate() {
    Map<String, Set<String>> granted = grantedToApp();
    for (String table : tables) {
      assertThat(granted.getOrDefault(table, Set.of()))
          .as("privileges of vigil_app on %s", table)
          .doesNotContain("DELETE", "TRUNCATE");
    }
  }

  private static Map<String, Set<String>> grantedToApp() {
    Map<String, Set<String>> granted = new HashMap<>();
    postgres
        .sql(
            "SELECT table_schema || '.' || table_name AS table_name, privilege_type"
                + " FROM information_schema.role_table_grants"
                + " WHERE grantee = 'vigil_app' AND table_schema = ANY (?)")
        .param(TableClasses.SCHEMAS.toArray(String[]::new))
        .query(
            (rs, rowNum) -> {
              granted
                  .computeIfAbsent(rs.getString("table_name"), t -> new HashSet<>())
                  .add(rs.getString("privilege_type"));
              return null;
            })
        .list();
    return granted;
  }
}
