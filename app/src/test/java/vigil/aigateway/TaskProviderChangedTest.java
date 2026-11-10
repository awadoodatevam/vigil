package vigil.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.TaskProviderChanged;

class TaskProviderChangedTest {

  @Test
  @DisplayName("AR-E-11 the event TaskProviderChanged carries tenant, task and provider key")
  void record() {
    assertThat(TaskProviderChanged.class.isRecord()).isTrue();
    assertThat(TaskProviderChanged.class.getPackageName()).isEqualTo("vigil.aigateway.api");
    assertThat(
            Arrays.stream(TaskProviderChanged.class.getRecordComponents())
                .map(c -> c.getType().getSimpleName() + " " + c.getName()))
        .containsExactly("TenantId tenantId", "AiTask task", "String providerKey");
    assertThat(Arrays.stream(AiTask.values()).map(AiTask::key))
        .containsExactly("SL-T-1", "SL-T-2", "SL-T-3");
  }
}
