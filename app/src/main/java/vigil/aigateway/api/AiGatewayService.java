package vigil.aigateway.api;

/** The requests of other modules to vigil-ai-gateway (PM-M18). */
public interface AiGatewayService {

  /**
   * SL-T-1 and SL-T-2: the providers of the task assignment in order, with data routing and
   * fallback (PM-M18-01 to PM-M18-04).
   *
   * <p>external call: not inside a transaction
   */
  CompletionResult complete(CompletionRequest request);

  /**
   * SL-T-3 with the named provider only (DM-E72-R2).
   *
   * <p>external call: not inside a transaction
   */
  EmbeddingResult embed(EmbeddingRequest request);
}
