package vigil.identity;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import vigil.shared.CanonicalJson;

/**
 * Password hashing (AR-S-01: Argon2id, 16-byte salt, 32-byte hash, parallelism 1, 19,456 KiB, 2
 * iterations) and session tokens (AR-S-02: 32 random bytes, only their SHA-256 stored).
 */
@Component
class Passwords {

  static final int TOKEN_BYTES = 32;

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  private final SecureRandom random = new SecureRandom();

  String hash(String password) {
    return encoder.encode(password);
  }

  boolean matches(String password, String hash) {
    return password != null && encoder.matches(password, hash);
  }

  /** A new session token: its 32 bytes. */
  byte[] newToken() {
    byte[] token = new byte[TOKEN_BYTES];
    random.nextBytes(token);
    return token;
  }

  /** The token as given to the caller: base64url without padding (43 characters). */
  static String encode(byte[] token) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  /** The 32 bytes of a token, or empty when the text is not base64url of 32 bytes. */
  static Optional<byte[]> decode(String token) {
    if (token == null) {
      return Optional.empty();
    }
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(token);
      return bytes.length == TOKEN_BYTES ? Optional.of(bytes) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** {@code token_hash}: SHA-256 of the token's bytes, 64 lowercase hexadecimal characters. */
  static String tokenHash(byte[] token) {
    return CanonicalJson.sha256Hex(token);
  }
}
