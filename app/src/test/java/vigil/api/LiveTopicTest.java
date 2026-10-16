package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiveTopicTest {

  @Test
  @DisplayName("AR-I-04 the topics of the live stream, their texts and refused texts")
  void topics() {
    assertThat(Arrays.stream(LiveTopic.Kind.values()).map(LiveTopic.Kind::key))
        .containsExactly(
            "incidents",
            "incident",
            "run",
            "alerts",
            "sources",
            "approval",
            "workflow",
            "tenant",
            "test-replay");
    String id = UUID.randomUUID().toString();
    for (String text :
        List.of(
            "incidents",
            "incident:" + id,
            "run:" + id,
            "alerts",
            "sources",
            "approval:" + id,
            "workflow",
            "tenant",
            "test-replay:" + id)) {
      assertThat(LiveTopic.parse(text).text()).isEqualTo(text);
    }
    for (String refused :
        List.of(
            "",
            "incident",
            "incidents:" + id,
            "incident:" + id.toUpperCase(Locale.ROOT),
            "incident:not-a-uuid",
            "alert",
            " incidents")) {
      assertThatThrownBy(() -> LiveTopic.parse(refused))
          .as("'%s'", refused)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new LiveTopic(LiveTopic.Kind.TENANT, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LiveTopic(LiveTopic.Kind.RUN, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("AR-TM-04 a heartbeat every 15 s and the end after 300 s")
  void timings() {
    LiveTimings timings = new ApiWebConfiguration(null).liveTimings();
    assertThat(timings.pingInterval()).isEqualTo(Duration.ofSeconds(15));
    assertThat(timings.maxOpen()).isEqualTo(Duration.ofSeconds(300));
  }
}
