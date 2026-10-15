package vigil.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The database secrets of one test JVM: three random 24-character passwords made at start
 * (BP-R-14), written to folders that each hold only the secrets one unit uses, files mode 0444 and
 * folders mode 0555 (BP-R-26). The folders are removed when the JVM ends.
 */
final class SecretFiles {

  static final String SUPERUSER = "vigil-db-superuser";
  static final String OWNER = "vigil-db-owner";
  static final String APP = "vigil-db-app";

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Map<String, String> values = new LinkedHashMap<>();
  private final List<Path> folders = new ArrayList<>();

  SecretFiles() {
    for (String name : List.of(SUPERUSER, OWNER, APP)) {
      values.put(name, randomPassword());
    }
    Runtime.getRuntime().addShutdownHook(new Thread(this::deleteFolders));
  }

  String value(String name) {
    return values.get(name);
  }

  /** A new folder holding only the named secrets. */
  synchronized Path folderWith(String... names) {
    try {
      Path folder = Files.createTempDirectory("vigil-secrets-");
      folders.add(folder);
      for (String name : names) {
        Path file = folder.resolve(name);
        Files.writeString(file, values.get(name));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
      }
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
      return folder;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String randomPassword() {
    StringBuilder password = new StringBuilder();
    for (int i = 0; i < 24; i++) {
      password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return password.toString();
  }

  private synchronized void deleteFolders() {
    for (Path folder : folders) {
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
}
