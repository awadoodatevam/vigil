package democontrol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** The secret files of demo-control (AR-S-04), read at each use; a secret is never logged. */
final class Secrets {

  private final Path folder;

  Secrets(Path folder) {
    this.folder = folder;
  }

  /** The secret's content, or empty when its file is missing, unreadable or empty. */
  Optional<String> read(String name) {
    try {
      String value = Files.readString(folder.resolve(name), StandardCharsets.UTF_8);
      return value.isEmpty() ? Optional.empty() : Optional.of(value);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** The secret's content, or an empty text when it cannot be read (the call then fails). */
  String value(String name) {
    return read(name).orElse("");
  }
}
