package vigil.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A new login session; {@code token} is the session token base64url-encoded without padding (43
 * characters), returned only here (AR-S-02).
 */
public record NewSession(UUID sessionId, String token, Instant expiresAt, UserView user) {}
