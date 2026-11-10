package vigil.investigation.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vigil.shared.Actor;

/** An AI investigation result (DM-E-68); empty values are {@code null}. */
public record InvestigationResultView(
    UUID id,
    UUID incidentId,
    Origin origin,
    UUID runId,
    Actor requestedBy,
    UUID requestGroupId,
    String providerKey,
    String modelName,
    String likelyCause,
    Verdict verdict,
    List<UUID> evidenceEntryIds,
    String uncertainty,
    List<RunbookRef> runbooksSupplied,
    List<RunbookRef> runbookRefs,
    String rawOutput,
    boolean rawOutputTruncated,
    Validation validation,
    List<String> rejectionReasons,
    Instant createdAt) {}
