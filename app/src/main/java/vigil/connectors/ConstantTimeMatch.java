package vigil.connectors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Compares a secret with a presented value in constant time: the SHA-256 digests of both, so the
 * time depends neither on where or whether they differ nor on their lengths (AR-S-05).
 */
final class ConstantTimeMatch {

  private ConstantTimeMatch() {}

  static boolean matches(String secret, String presented) {
    if (secret == null || presented == null) {
      return false;
    }
    return MessageDigest.isEqual(digest(secret), digest(presented));
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
