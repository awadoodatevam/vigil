package vigil.intake.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A monitoring source (DM-E-15). {@code lastContactAt}, {@code lastTestAt}, {@code lastTestOutcome}
 * and {@code lastTestReason} are {@code null} when empty.
 */
public record SourceView(
    UUID id,
    String sourceKey,
    String name,
    MonitoringKind sourceKind,
    UUID connectorInstanceId,
    Duration healthCheckInterval,
    Duration staleAfter,
    Duration disconnectedAfter,
    Health health,
    Instant healthChangedAt,
    Instant lastContactAt,
    Instant lastTestAt,
    TestOutcome lastTestOutcome,
    String lastTestReason,
    boolean active) {}
