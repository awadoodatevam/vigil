package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiEmbedding;
import vigil.connectors.api.AiFailure;
import vigil.connectors.api.AiFinish;
import vigil.connectors.api.AiPromptPart;
import vigil.connectors.api.AiProviderConnector;

class AiProviderTypeTest {

  @Test
  @DisplayName("AR-K-00e the AI provider type offers exactly kind, complete and embed")
  void signatures() {
    assertThat(AiProviderConnector.class.isInterface()).isTrue();
    assertThat(AiProviderConnector.class.getPackageName()).isEqualTo("vigil.connectors.api");
    List<String> methods =
        Arrays.stream(AiProviderConnector.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic() && !Modifier.isStatic(method.getModifiers()))
            .map(AiProviderTypeTest::signature)
            .sorted()
            .toList();
    assertThat(methods)
        .containsExactly(
            "AiCompletion complete(TenantId,ConnectorInstanceView,String,String,List,String,"
                + "JsonNode,int)",
            "AiEmbedding embed(TenantId,ConnectorInstanceView,String,List)",
            "ConnectorKind kind()");
    assertThat(AiCompletion.class.isSealed()).isTrue();
    assertThat(AiCompletion.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(AiCompletion.Answered.class, AiCompletion.Failed.class);
    assertThat(AiEmbedding.class.isSealed()).isTrue();
    assertThat(AiEmbedding.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(AiEmbedding.Embedded.class, AiEmbedding.Failed.class);
    assertThat(components(AiPromptPart.class)).containsExactly("label", "categoryName", "text");
    assertThat(components(AiCompletion.Answered.class))
        .containsExactly("outputText", "finish", "inputUnits", "outputUnits");
    assertThat(components(AiCompletion.Failed.class)).containsExactly("failure", "reason");
    assertThat(components(AiEmbedding.Embedded.class)).containsExactly("vectors", "inputUnits");
    assertThat(components(AiEmbedding.Failed.class)).containsExactly("failure", "reason");
    assertThat(Arrays.stream(AiFinish.values()).map(Enum::name))
        .containsExactly("COMPLETE", "TRUNCATED", "REFUSED");
    assertThat(Arrays.stream(AiFailure.values()).map(Enum::name))
        .containsExactly("UNREACHABLE", "PROVIDER_ERROR");
    assertThat(AiProviderConnector.class).isAssignableFrom(ClaudeConnector.class);
    assertThat(AiProviderConnector.class).isAssignableFrom(OpenAiConnector.class);
    assertThat(AiProviderConnector.class).isAssignableFrom(LocalModelConnector.class);
    assertThat(AiProviderConnector.class).isAssignableFrom(LocalEmbeddingConnector.class);
  }

  private static List<String> components(Class<?> record) {
    assertThat(record.isRecord()).as(record.getName()).isTrue();
    return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
  }

  private static String signature(Method method) {
    return method.getReturnType().getSimpleName()
        + " "
        + method.getName()
        + "("
        + String.join(
            ",", Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toList())
        + ")";
  }
}
