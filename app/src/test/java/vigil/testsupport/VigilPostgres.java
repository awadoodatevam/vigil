package vigil.testsupport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The PostgreSQL 17 container with pgvector of this test JVM, started once, initialised by {@code
 * deploy/db/init/} with the roles {@code vigil_owner} and {@code vigil_app} (AR-U-02).
 */
public final class VigilPostgres {

  static final String IMAGE =
      "pgvector/pgvector:0.8.7-pg17"
          + "@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d";
  private static final String SECRETS_IN_CONTAINER = "/run/secrets/vigil";
  private static final int POSTGRES_PORT = 5432;

  private static VigilPostgres instance;

  private final SecretFiles secrets = new SecretFiles();
  private final GenericContainer<?> container;

  private VigilPostgres() {
    Path containerSecrets =
        secrets.folderWith(SecretFiles.SUPERUSER, SecretFiles.OWNER, SecretFiles.APP);
    container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withEnv(
                Map.of(
                    "POSTGRES_USER", "postgres",
                    "POSTGRES_DB", "vigil",
                    "POSTGRES_PASSWORD_FILE", SECRETS_IN_CONTAINER + "/" + SecretFiles.SUPERUSER))
            .withCopyToContainer(
                MountableFile.forHostPath(Path.of("../deploy/db/init")),
                "/docker-entrypoint-initdb.d/")
            .withCopyToContainer(MountableFile.forHostPath(containerSecrets), SECRETS_IN_CONTAINER)
            .withExposedPorts(POSTGRES_PORT)
            .waitingFor(
                Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    container.start();
  }

  /** The running container, started on first use. */
  public static synchronized VigilPostgres get() {
    if (instance == null) {
      instance = new VigilPostgres();
    }
    return instance;
  }

  public String host() {
    return container.getHost();
  }

  public int port() {
    return container.getMappedPort(POSTGRES_PORT);
  }

  String password(String secretName) {
    return secrets.value(secretName);
  }

  /** A new folder holding only the named secrets ({@code vigil-db-owner}, {@code vigil-db-app}). */
  Path secretsFolderWith(String... names) {
    return secrets.folderWith(names);
  }

  String jdbcUrl(String database) {
    return "jdbc:postgresql://" + host() + ":" + port() + "/" + database;
  }
}
