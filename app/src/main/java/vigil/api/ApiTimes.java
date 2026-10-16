package vigil.api;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** The timestamp format of vigil-api: {@code YYYY-MM-DDTHH:MM:SS.sssZ} in UTC (AR-I-01). */
final class ApiTimes {

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private ApiTimes() {}

  /** Writes {@code instant} truncated to milliseconds (Example: 2026-10-16T09:05:00.120Z). */
  static String format(Instant instant) {
    return FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
  }
}
