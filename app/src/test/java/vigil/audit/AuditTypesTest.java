package vigil.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.audit.api.ConfigurationArea;

class AuditTypesTest {

  @Test
  @DisplayName("DM-E08-R1 the 18 audit event types in order; the 10 configuration areas of DM-V-11")
  void eventTypesAndAreas() {
    assertThat(Arrays.stream(AuditEventType.values()).map(AuditEventType::key))
        .containsExactly(
            "incident_created",
            "alert_grouped",
            "hypothesis_changed",
            "assignment_changed",
            "incident_acknowledged",
            "incident_escalated",
            "ai_result_accepted",
            "ai_result_rejected",
            "policy_check_result",
            "approval_requested",
            "approval_decided",
            "action_executed",
            "action_reconciled",
            "recovery_verified",
            "incident_state_changed",
            "notification_sent",
            "ticket_changed",
            "configuration_changed");
    assertThat(Arrays.stream(ConfigurationArea.values()).map(ConfigurationArea::key))
        .containsExactly(
            "tenant_settings",
            "users_and_teams",
            "sources",
            "service_map",
            "routing_rules",
            "escalation_levels",
            "workflows",
            "ai_settings",
            "registered_actions",
            "seed_import");
  }

  @Test
  @DisplayName("DM-E08-R2 the audit service offers no operation that changes or deletes an event")
  void noChangeOrDelete() {
    assertThat(Arrays.stream(AuditService.class.getDeclaredMethods()).map(Method::getName))
        .containsExactlyInAnyOrder("append", "query");
  }

  @Test
  @DisplayName("PM-M03-02 AuditService declares exactly append and query")
  void onlyAppendAndQuery() {
    assertThat(AuditService.class.getDeclaredMethods()).hasSize(2);
    assertThat(Arrays.stream(AuditService.class.getMethods()).map(Method::getName))
        .containsExactlyInAnyOrder("append", "query");
  }
}
