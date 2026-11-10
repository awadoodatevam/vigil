package vigil.aigateway.api;

import java.util.List;

/** The result of {@link AiGatewayService#embed}. */
public sealed interface EmbeddingResult
    permits EmbeddingResult.Embedded, EmbeddingResult.Unavailable {

  /** One vector per text, in the order of the texts. */
  record Embedded(String providerKey, String modelName, List<float[]> vectors, long inputUnits)
      implements EmbeddingResult {}

  /** The named provider gave no vectors. */
  record Unavailable(UsageOutcome outcome) implements EmbeddingResult {}
}
