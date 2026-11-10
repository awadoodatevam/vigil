package vigil.knowledge.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A similarity search result (DM-E-66); empty values are {@code null}. */
public record SimilarityResultView(
    UUID id,
    UUID incidentId,
    UUID runId,
    String searchText,
    String providerKey,
    SearchOutcome outcome,
    String bestPastIncidentKey,
    BigDecimal score,
    Long successCount,
    String fixActionKey,
    BigDecimal minSimilarity,
    Instant searchedAt) {}
