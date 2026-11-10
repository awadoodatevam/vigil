package vigil.aigateway.api;

/** The result of {@link AiGatewayService#complete}. */
public sealed interface CompletionResult
    permits CompletionResult.Answered, CompletionResult.Unavailable {

  /**
   * A provider's 2xx answer at {@code fallbackPosition}; {@code outputText} is {@code null} when
   * the answer has none.
   */
  record Answered(
      String providerKey,
      ProviderLocation location,
      String modelName,
      int fallbackPosition,
      String outputText,
      AnswerFinish finish,
      long inputUnits,
      long outputUnits)
      implements CompletionResult {}

  /** No provider answered (DM-R-45, SL-A2-05). */
  record Unavailable(UnavailableReason reason) implements CompletionResult {}
}
