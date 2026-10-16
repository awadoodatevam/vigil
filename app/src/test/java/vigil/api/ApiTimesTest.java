package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiTimesTest {

  @Test
  @DisplayName("AR-I-01 timestamps are written in UTC with milliseconds")
  void timestamps() {
    assertThat(ApiTimes.format(Instant.parse("2026-10-16T09:05:00Z")))
        .isEqualTo("2026-10-16T09:05:00.000Z");
    assertThat(ApiTimes.format(Instant.parse("2026-10-16T09:05:00.1Z")))
        .isEqualTo("2026-10-16T09:05:00.100Z");
    assertThat(ApiTimes.format(Instant.parse("2026-10-16T09:05:00.123456789Z")))
        .isEqualTo("2026-10-16T09:05:00.123Z");
  }
}
