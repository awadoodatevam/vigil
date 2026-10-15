package vigil.shared;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Identifiers: UUID version 7 (RFC 9562 Section 6.2, Method 1) with a 12-bit counter, so ids made
 * by one process are strictly increasing (AR-P-01).
 */
public final class Ids {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int COUNTER_START_BOUND = 2048;
  private static final int COUNTER_MAX = 4095;

  private static long lastMillis = -1;
  private static int counter;

  private Ids() {}

  /** A new UUID version 7. */
  public static synchronized UUID newId() {
    long now = System.currentTimeMillis();
    if (now > lastMillis) {
      lastMillis = now;
      counter = RANDOM.nextInt(COUNTER_START_BOUND);
    } else if (counter < COUNTER_MAX) {
      counter++;
    } else {
      lastMillis++;
      counter = RANDOM.nextInt(COUNTER_START_BOUND);
    }
    long mostSignificant = (lastMillis << 16) | 0x7000L | counter;
    long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(mostSignificant, leastSignificant);
  }
}
