package vigil.shared;

import java.util.regex.Pattern;

/** A key: 1 to 64 characters from {@code A-Z a-z 0-9 . _ -}, case-sensitive (DM-C-01). */
public record Key(String value) implements Comparable<Key> {

  private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  public Key {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid key");
    }
  }

  /** Orders by Unicode code points (DM-C-01). */
  @Override
  public int compareTo(Key other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
