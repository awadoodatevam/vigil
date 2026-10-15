package vigil.testsupport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * A database in the {@link VigilPostgres} container, migrated once by running the built JAR as the
 * unit {@code vigil-migrate} does (AR-U-01, AR-P-05).
 */
public final class VigilDatabase {

  private static final Path JAR = Path.of("target/vigil-app.jar");
  private static final long MIGRATE_TIMEOUT_SECONDS = 180;
  private static final AtomicInteger COUNTER = new AtomicInteger();

  private static VigilDatabase main;

  private final VigilPostgres postgres;
  private final String name;
  private final int migrateExitCode;

  private VigilDatabase(VigilPostgres postgres, String name) {
    this.postgres = postgres;
    this.name = name;
    this.migrateExitCode = migrate();
    if (migrateExitCode != 0) {
      throw new IllegalStateException(
          "vigil-migrate exited with " + migrateExitCode + " for database " + name);
    }
  }

  /** Database {@code vigil}, made by the init script. */
  public static synchronized VigilDatabase main() {
    if (main == null) {
      main = new VigilDatabase(VigilPostgres.get(), "vigil");
    }
    return main;
  }

  /** A new database {@code vigil_t{n}} with the grants and extension of the init script. */
  public static VigilDatabase fresh() {
    VigilPostgres postgres = VigilPostgres.get();
    String name = "vigil_t" + COUNTER.incrementAndGet();
    JdbcClient server = postgresClient(postgres, "vigil");
    server.sql("CREATE DATABASE " + name).update();
    server.sql("GRANT CONNECT, CREATE ON DATABASE " + name + " TO vigil_owner").update();
    server.sql("GRANT CONNECT ON DATABASE " + name + " TO vigil_app").update();
    postgresClient(postgres, name).sql("CREATE EXTENSION IF NOT EXISTS vector").update();
    return new VigilDatabase(postgres, name);
  }

  public String name() {
    return name;
  }

  public String host() {
    return postgres.host();
  }

  public int port() {
    return postgres.port();
  }

  public String jdbcUrl() {
    return postgres.jdbcUrl(name);
  }

  /** The exit code of the {@code vigil-migrate} child process. */
  public int migrateExitCode() {
    return migrateExitCode;
  }

  /** A client connected as the superuser {@code postgres}, for tests that read the catalogue. */
  public JdbcClient postgres() {
    return postgresClient(postgres, name);
  }

  /** A client connected as the runtime role {@code vigil_app}. */
  public SimpleDriverDataSource appDataSource() {
    return new SimpleDriverDataSource(
        new Driver(), jdbcUrl(), "vigil_app", postgres.password(SecretFiles.APP));
  }

  /** A new folder holding only {@code vigil-db-app}, as the secret folder of vigil-app. */
  public Path appSecretsFolder() {
    return postgres.secretsFolderWith(SecretFiles.APP);
  }

  /** The {@code java} executable of the running test JVM. */
  public static String javaExecutable() {
    return ProcessHandle.current().info().command().orElseThrow();
  }

  private static JdbcClient postgresClient(VigilPostgres postgres, String database) {
    return JdbcClient.create(
        new SimpleDriverDataSource(
            new Driver(),
            postgres.jdbcUrl(database),
            "postgres",
            postgres.password(SecretFiles.SUPERUSER)));
  }

  private int migrate() {
    ProcessBuilder builder =
        new ProcessBuilder(javaExecutable(), "-jar", JAR.toString())
            .redirectErrorStream(true)
            .redirectOutput(new File("target/vigil-migrate-" + name + ".log"));
    builder.environment().put("SPRING_PROFILES_ACTIVE", "migrate");
    builder
        .environment()
        .put("VIGIL_SECRETS_DIR", postgres.secretsFolderWith(SecretFiles.OWNER).toString());
    builder.environment().put("VIGIL_DB_HOST", host());
    builder.environment().put("VIGIL_DB_PORT", Integer.toString(port()));
    builder.environment().put("VIGIL_DB_NAME", name);
    try {
      Process process = builder.start();
      if (!process.waitFor(MIGRATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("vigil-migrate did not finish for database " + name);
      }
      return process.exitValue();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
