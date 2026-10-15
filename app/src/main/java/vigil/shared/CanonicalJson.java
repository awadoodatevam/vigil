package vigil.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

/** Canonical JSON (RFC 8785) and SHA-256 in lowercase hexadecimal (AR-P-03). */
public final class CanonicalJson {

  private CanonicalJson() {}

  /** The RFC 8785 canonical form of {@code json}. */
  public static String canonicalize(String json) {
    try {
      return new JsonCanonicalizer(json).getEncodedString();
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("not valid JSON", e);
    }
  }

  /** SHA-256 of {@code bytes} as 64 lowercase hexadecimal characters. */
  public static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** SHA-256 over the UTF-8 bytes of the canonical form of {@code json}. */
  public static String sha256HexOfCanonical(String json) {
    return sha256Hex(canonicalize(json).getBytes(StandardCharsets.UTF_8));
  }
}
