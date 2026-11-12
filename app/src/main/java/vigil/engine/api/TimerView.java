package vigil.engine.api;

import java.time.Instant;
import java.util.UUID;

/** A {@code pending} durable timer (DM-E-52): its step execution and when it is due. */
public record TimerView(UUID id, UUID stepExecutionId, Instant dueAt) {}
