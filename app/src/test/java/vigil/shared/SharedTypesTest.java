package vigil.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SharedTypesTest {

  @Test
  @DisplayName("AR-R-06 vigil.shared holds exactly the 13 public types of the AR-R-06 list")
  void sharedHoldsExactlyTheListedTypes() {
    List<String> publicTopLevel =
        new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vigil.shared")
                .stream()
                .filter(type -> type.getPackageName().equals("vigil.shared"))
                .filter(JavaClass::isTopLevelClass)
                .filter(type -> type.getModifiers().contains(JavaModifier.PUBLIC))
                .map(JavaClass::getSimpleName)
                .toList();
    assertThat(publicTopLevel)
        .containsExactlyInAnyOrder(
            "Ids",
            "Key",
            "Actor",
            "TenantId",
            "Timestamps",
            "CanonicalJson",
            "VigilError",
            "TransactionGuard",
            "StepCall",
            "StepCancel",
            "StepAnswer",
            "Completed",
            "Waiting");
  }

  @Test
  @DisplayName("AR-R-06 the step protocol types carry the keys of DM-S-01 to DM-S-11 and AR-R-21")
  void stepProtocolTypes() {
    assertThat(Arrays.stream(StepCall.StepType.values()).map(StepCall.StepType::key))
        .containsExactly(
            "trigger",
            "condition",
            "diagnostic",
            "ai_task",
            "approval",
            "action",
            "recovery_check",
            "escalation",
            "notification",
            "ticket",
            "wait");
    assertThat(Arrays.stream(Waiting.WakeEvent.values()).map(Waiting.WakeEvent::key))
        .containsExactly(
            "alert_state_changed",
            "source_health_changed",
            "incident_acknowledged",
            "incident_state_changed",
            "approval_decided");
    for (String reason :
        List.of("start", "timer", "retry", "resume", "restart", "event:approval_decided")) {
      assertThat(new StepCall.WakeReason(reason).value()).isEqualTo(reason);
    }
    for (String reason : List.of("event:incident_created", "event:", "wake", "")) {
      assertThatThrownBy(() -> new StepCall.WakeReason(reason))
          .isInstanceOf(IllegalArgumentException.class);
    }
    StepCall call =
        new StepCall(
            new TenantId(Ids.newId()),
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            new Key("check"),
            StepCall.StepType.RECOVERY_CHECK,
            "{}",
            1,
            null,
            new StepCall.WakeReason("start"),
            new Key("check-key"),
            null);
    assertThat(call.earlierApprovers()).isEmpty();
    assertThatThrownBy(
            () ->
                new StepCall(
                    call.tenantId(),
                    call.runId(),
                    call.incidentId(),
                    call.stepExecutionId(),
                    call.stepId(),
                    StepCall.StepType.ACTION,
                    "{}",
                    1,
                    null,
                    call.wakeReason(),
                    new Key("check-key"),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    StepAnswer answer = new Completed(new Key("done"), null);
    assertThat(answer).isInstanceOf(Completed.class);
    assertThat(Actor.module("vigil-engine").kind()).isEqualTo(Actor.Kind.MODULE);
    assertThatThrownBy(() -> new Actor(Actor.Kind.USER, null, "vigil-engine"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("AR-P-01 ids are UUID version 7, strictly increasing, with millisecond time")
  void idsAreVersion7AndIncreasing() {
    long before = System.currentTimeMillis();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(Ids.newId());
    }
    long after = System.currentTimeMillis();
    for (int i = 0; i < ids.size(); i++) {
      UUID id = ids.get(i);
      assertThat(id.version()).isEqualTo(7);
      assertThat(id.variant()).isEqualTo(2);
      long millis = id.getMostSignificantBits() >>> 16;
      assertThat(millis).isBetween(before, after + 10);
      if (i > 0) {
        assertThat(compareUnsigned(ids.get(i - 1), id))
            .as("id %d after id %d", i, i - 1)
            .isNegative();
      }
    }
  }

  @Test
  @DisplayName("DM-C-01 keys are 1 to 64 characters of A-Z a-z 0-9 . _ -")
  void keyFormat() {
    assertThat(new Key("a").value()).isEqualTo("a");
    String longest = "Az09._-".repeat(9) + "x";
    assertThat(longest).hasSize(64);
    assertThat(new Key(longest).toString()).isEqualTo(longest);
    for (String bad : new String[] {"", longest + "y", "a b", "é", null}) {
      assertThatThrownBy(() -> new Key(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new Key("B").compareTo(new Key("a"))).isNegative();
  }

  @Test
  @DisplayName("DM-C-08 timestamps are UTC with millisecond precision")
  void clockIsUtcAndMillisecond() {
    Clock fixed = Clock.fixed(Instant.parse("2026-10-15T08:00:00.123456789Z"), ZoneOffset.UTC);
    assertThat(Timestamps.now(fixed)).isEqualTo(Instant.parse("2026-10-15T08:00:00.123Z"));
    assertThat(new Timestamps.ClockConfiguration().clock().getZone()).isEqualTo(ZoneOffset.UTC);
  }

  private static int compareUnsigned(UUID first, UUID second) {
    int high =
        Long.compareUnsigned(first.getMostSignificantBits(), second.getMostSignificantBits());
    return high != 0
        ? high
        : Long.compareUnsigned(first.getLeastSignificantBits(), second.getLeastSignificantBits());
  }
}
