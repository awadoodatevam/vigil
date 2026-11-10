package vigil.connectors.api;

/** The result of {@link AiProviderConnector#complete}. */
public sealed interface AiCompletion permits AiCompletion.Answered, AiCompletion.Failed {

  /** A 2xx answer; {@code outputText} is {@code null} when the answer has none. */
  record Answered(String outputText, AiFinish finish, long inputUnits, long outputUnits)
      implements AiCompletion {}

  /** No answer, or an answer outside 200 to 299, with a reason that holds no secret. */
  record Failed(AiFailure failure, String reason) implements AiCompletion {}
}
