package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.testsupport.TableClasses;
import vigil.testsupport.VigilDatabase;

class MigrateIT {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

  private static VigilDatabase database;
  private static JdbcClient postgres;

  @BeforeAll
  static void migrate() {
    database = VigilDatabase.main();
    postgres = database.postgres();
  }

  @Test
  @DisplayName("AR-U-01 vigil-migrate exits 0 and migrates as vigil_owner")
  void migrateRunsAsOwner() {
    assertThat(database.migrateExitCode()).isZero();
    List<String> installers =
        postgres
            .sql(
                "SELECT DISTINCT installed_by FROM platform.flyway_schema_history"
                    + " WHERE version IS NOT NULL")
            .query(String.class)
            .list();
    assertThat(installers).containsExactly("vigil_owner");
  }

  @Test
  @DisplayName("AR-P-04 the database has exactly the 23 schemas, owned by vigil_owner")
  void schemas() {
    List<Map<String, Object>> rows =
        postgres
            .sql(
                "SELECT n.nspname AS schema, pg_get_userbyid(n.nspowner) AS owner"
                    + " FROM pg_namespace n WHERE n.nspname NOT IN ('public', 'information_schema')"
                    + " AND n.nspname NOT LIKE 'pg\\_%'")
            .query()
            .listOfRows();
    assertThat(rows)
        .extracting(row -> row.get("schema"))
        .containsExactlyInAnyOrderElementsOf(TableClasses.SCHEMAS);
    assertThat(rows).extracting(row -> row.get("owner")).containsOnly("vigil_owner");
  }

  @Test
  @DisplayName("AR-P-05 migration files follow the folder and name rule and are applied")
  void migrationLayout() throws IOException {
    List<Path> files;
    try (Stream<Path> paths = Files.walk(MIGRATIONS)) {
      files = paths.filter(Files::isRegularFile).toList();
    }
    assertThat(files).isNotEmpty();
    for (Path file : files) {
      Path relative = MIGRATIONS.relativize(file);
      assertThat(relative.getNameCount()).as("%s is in a schema folder", relative).isEqualTo(2);
      String schema = relative.getName(0).toString();
      assertThat(TableClasses.SCHEMAS).as("folder of %s", relative).contains(schema);
      assertThat(relative.getFileName().toString())
          .as("name of %s", relative)
          .matches("V\\d{14}__" + Pattern.quote(schema) + "_[a-z0-9_]+\\.sql");
    }
    List<String> applied =
        postgres
            .sql("SELECT script FROM platform.flyway_schema_history WHERE version IS NOT NULL")
            .query(String.class)
            .list();
    assertThat(applied)
        .containsExactlyInAnyOrderElementsOf(
            files.stream().map(MIGRATIONS::relativize).map(p -> p.toString()).toList());
  }

  @Test
  @DisplayName("AR-T-05 PostgreSQL 17 with pgvector 0.8")
  void postgresWithPgvector() {
    assertThat(postgres.sql("SELECT version()").query(String.class).single())
        .startsWith("PostgreSQL 17");
    assertThat(
            postgres
                .sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                .query(String.class)
                .single())
        .startsWith("0.8.");
  }

  @Test
  @DisplayName("AR-T-06 Flyway history has no failed migration")
  void noFailedMigration() {
    assertThat(
            postgres
                .sql("SELECT count(*) FROM platform.flyway_schema_history WHERE NOT success")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("AR-U-02 the init script creates the two login roles, neither a superuser")
  void roles() {
    List<Map<String, Object>> roles =
        postgres
            .sql(
                "SELECT rolname, rolcanlogin, rolsuper FROM pg_roles"
                    + " WHERE rolname IN ('vigil_owner', 'vigil_app') ORDER BY rolname")
            .query()
            .listOfRows();
    assertThat(roles)
        .containsExactly(
            Map.of("rolname", "vigil_app", "rolcanlogin", true, "rolsuper", false),
            Map.of("rolname", "vigil_owner", "rolcanlogin", true, "rolsuper", false));
  }
}
