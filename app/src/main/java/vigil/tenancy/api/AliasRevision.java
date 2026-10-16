package vigil.tenancy.api;

import java.time.Instant;
import vigil.shared.TenantId;

/** One revision of a tenant alias (DM-E-83). */
public record AliasRevision(
    String hostName, long revision, TenantId tenantId, Instant setAt, String setBy) {}
