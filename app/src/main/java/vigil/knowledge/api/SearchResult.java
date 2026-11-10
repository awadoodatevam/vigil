package vigil.knowledge.api;

/** The result of {@link KnowledgeService#search}. */
public sealed interface SearchResult permits SearchResult.Searched, SearchResult.Unavailable {

  /** {@code bestPastIncident} is the past incident of a match, {@code null} for no_match. */
  record Searched(SimilarityResultView result, PastIncidentView bestPastIncident)
      implements SearchResult {}

  /** No search could be made. */
  record Unavailable(Reason reason) implements SearchResult {}

  /** Why no search could be made. */
  enum Reason {
    REEMBEDDING,
    UNREACHABLE,
    PROVIDER_ERROR,
    REFUSED_BY_DATA_ROUTING
  }
}
