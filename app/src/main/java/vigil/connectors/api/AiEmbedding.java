package vigil.connectors.api;

import java.util.List;

/** The result of {@link AiProviderConnector#embed}. */
public sealed interface AiEmbedding permits AiEmbedding.Embedded, AiEmbedding.Failed {

  /** One vector per text, in the order of the texts. */
  record Embedded(List<float[]> vectors, long inputUnits) implements AiEmbedding {}

  /** No usable answer, with a reason that holds no secret. */
  record Failed(AiFailure failure, String reason) implements AiEmbedding {}
}
