package vigil.tenancy.api;

import java.time.Instant;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/** A tenant (DM-E-01); {@code readyAt} is {@code null} until the tenant is ready. */
public record TenantView(
    TenantId id,
    String name,
    TenantState state,
    Instant createdAt,
    Actor createdBy,
    Instant readyAt) {}
