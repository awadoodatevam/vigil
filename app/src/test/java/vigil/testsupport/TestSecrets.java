package vigil.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A secret folder of vigil-app for one test: the database's {@code vigil-db-app} file and one file
 * per given secret, files mode 0444 and folder 0555 (BP-R-26), removed when the JVM ends. Tests
 * generate the values at start (BP-R-14).
 */
public final class TestSecrets {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String READ_ONLY_FILE = "r--r--r--";
  private static final String READ_ONLY_FOLDER = "r-xr-xr-x";

  private final Path folder;
  private final Map<String, String> values = new LinkedHashMap<>();

  private TestSecrets(Path folder) {
    this.folder = folder;
    Runtime.getRuntime().addShutdownHook(new Thread(this::delete));
  }

  /** A new folder with the database's {@code vigil-db-app} file and the given secrets. */
  public static TestSecrets create(VigilDatabase database, Map<String, String> secrets) {
    try {
      TestSecrets created = new TestSecrets(Files.createTempDirectory("vigil-test-secrets-"));
      created.write(SecretFiles.APP, database.appPassword());
      secrets.forEach(created::write);
      created.values.putAll(secrets);
      Files.setPosixFilePermissions(
          created.folder, PosixFilePermissions.fromString(READ_ONLY_FOLDER));
      return created;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 32 random bytes, base64url without padding: 43 characters (AR-S-05). */
  public static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public Path folder() {
    return folder;
  }

  /** The value of a secret given to {@link #create} or {@link #replace}. */
  public String value(String name) {
    String value = values.get(name);
    if (value == null) {
      throw new IllegalArgumentException("no secret " + name);
    }
    return value;
  }

  /** Writes a new value for a secret file (or a new file). */
  public synchronized void replace(String name, String value) {
    changing(
        () -> {
          Path file = folder.resolve(name);
          if (Files.exists(file)) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
          }
          write(name, value);
          values.put(name, value);
        });
  }

  /** Removes a secret file. */
  public synchronized void remove(String name) {
    changing(
        () -> {
          Files.deleteIfExists(folder.resolve(name));
          values.remove(name);
        });
  }

  private interface Change {
    void run() throws IOException;
  }

  private void changing(Change change) {
    try {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
      try {
        change.run();
      } finally {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString(READ_ONLY_FOLDER));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void write(String name, String value) {
    try {
      Path file = folder.resolve(name);
      Files.writeString(file, value, StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(READ_ONLY_FILE));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private synchronized void delete() {
    try (Stream<Path> paths = Files.walk(folder)) {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      // Best effort at JVM exit.
    }
  }
}
