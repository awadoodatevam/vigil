package democontrol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * The control panel's login (AR-S-12): one user {@code operator} whose password is the secret
 * {@code control-password}, kept only as its Argon2id hash (AR-S-01); sessions of 12 h kept in
 * memory as the SHA-256 of their token (spec 6.3 items 1 and 2).
 */
class Login {

  static final String USERNAME = "operator";
  static final String COOKIE = "control_session";
  static final Duration LIFETIME = Duration.ofHours(12);

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;
  private final String hash;
  private final Map<String, Instant> sessions = new ConcurrentHashMap<>();

  Login(Secrets secrets, Clock clock) {
    this.clock = clock;
    this.hash = secrets.read("control-password").map(encoder::encode).orElse(null);
  }

  /** True when the username and password are the operator's (never without the secret). */
  boolean verify(String username, String password) {
    boolean user =
        MessageDigest.isEqual(
            username.getBytes(StandardCharsets.UTF_8), USERNAME.getBytes(StandardCharsets.UTF_8));
    boolean matches = hash != null && encoder.matches(password, hash);
    return user && matches;
  }

  /** A new session: its token for the cookie. */
  String start() {
    byte[] token = new byte[32];
    random.nextBytes(token);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    sessions.put(sha256(encoded), clock.instant().plus(LIFETIME));
    return encoded;
  }

  /** True when the token names a session that has not ended. */
  boolean valid(String token) {
    if (token == null || token.isEmpty()) {
      return false;
    }
    Instant ends = sessions.get(sha256(token));
    return ends != null && clock.instant().isBefore(ends);
  }

  private static String sha256(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
