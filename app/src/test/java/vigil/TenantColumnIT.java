package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.testsupport.TableClasses;
import vigil.testsupport.VigilDatabase;

class TenantColumnIT {

  /** The tables without tenant_id (AR-Q-08, complete list). */
  private static final Set<String> INSTALLATION_LEVEL =
      Set.of(
          "tenancy.tenant",
          "tenancy.tenant_alias",
          "platform.event_publication",
          "platform.flyway_schema_history");

  private static JdbcClient postgres;
  private static List<TableClasses.Row> rows;

  @BeforeAll
  static void read() {
    postgres = VigilDatabase.main().postgres();
    rows = TableClasses.rows();
  }

  @Test
  @DisplayName("AR-Q-08 every tenant-scoped table has tenant_id and its indexes start with it")
  void tenantScopedTablesHaveTenantId() {
    for (TableClasses.Row row : rows.stream().filter(TableClasses.Row::tenantScoped).toList()) {
      String[] name = row.table().split("\\.");
      List<Map<String, Object>> column =
          postgres
              .sql(
                  "SELECT data_type, is_nullable FROM information_schema.columns"
                      + " WHERE table_schema = ? AND table_name = ? AND column_name = 'tenant_id'")
              .params(name[0], name[1])
              .query()
              .listOfRows();
      assertThat(column)
          .as("tenant_id of %s", row.table())
          .containsExactly(Map.of("data_type", "uuid", "is_nullable", "NO"));
      List<String> firstColumns =
          postgres
              .sql(
                  "SELECT a.attname FROM pg_index i"
                      + " JOIN pg_class t ON t.oid = i.indrelid"
                      + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                      + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = i.indkey[0]"
                      + " WHERE n.nspname = ? AND t.relname = ? AND NOT i.indisprimary")
              .params(name[0], name[1])
              .query(String.class)
              .list();
      assertThat(firstColumns)
          .as("first columns of the indexes of %s", row.table())
          .containsOnly("tenant_id");
    }
  }

  @Test
  @DisplayName("DM-C-02 the tables without tenant_id are exactly the installation-level ones")
  void tablesWithoutTenantId() {
    List<String> existing =
        postgres
            .sql(
                "SELECT table_schema || '.' || table_name FROM information_schema.tables"
                    + " WHERE table_type = 'BASE TABLE' AND table_schema = ANY (?)")
            .param(TableClasses.SCHEMAS.toArray(String[]::new))
            .query(String.class)
            .list();
    List<String> expected = existing.stream().filter(INSTALLATION_LEVEL::contains).toList();
    assertThat(rows.stream().filter(row -> !row.tenantScoped()).map(TableClasses.Row::table))
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(expected).contains("platform.event_publication", "platform.flyway_schema_history");
  }
}
