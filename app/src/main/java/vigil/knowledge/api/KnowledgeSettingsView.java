package vigil.knowledge.api;

import java.math.BigDecimal;

/** The knowledge settings (DM-E-67); {@code reembeddingProviderKey} {@code null} when ready. */
public record KnowledgeSettingsView(
    BigDecimal minSimilarity,
    long runbookLimit,
    String embeddingProviderKey,
    EmbeddingState embeddingState,
    String reembeddingProviderKey) {}
