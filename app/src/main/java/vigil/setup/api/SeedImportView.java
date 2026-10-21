package vigil.setup.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vigil.shared.TenantId;

/**
 * A seed import record (DM-E-82); {@code startedAt} is the import start time, {@code finishedAt} is
 * {@code null} while it runs; {@code parts} are the 17 parts in the order of DM-R-56.
 */
public record SeedImportView(
    UUID id,
    TenantId tenantId,
    String bundleName,
    String bundleVersion,
    SeedImportState state,
    Instant startedAt,
    Instant finishedAt,
    List<PartView> parts) {}
