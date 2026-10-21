package vigil.setup.api;

import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of a tenant (PM-M22-03, DM-E-82). Requests of vigil-api and the restart. */
public interface SeedImports {

  /**
   * Checks the bundle file (AR-B-05, AR-B-07) and starts its import into a new tenant on its own
   * virtual thread (AR-R-31). Refuses with {@code NOT_FOUND} for an unknown tenant, {@code REFUSED}
   * {@code tenant_not_new} for a tenant that is not new (DM-E82-R1) and {@code INVALID} with the
   * bundle's problems.
   */
  SeedImportView start(TenantId tenantId, byte[] bundle);

  /** The record of {@code seedImportId}, of any tenant (installation-level, AR-S-07). */
  Optional<SeedImportView> seedImport(UUID seedImportId);

  /** AR-R-24 step (2): fails every record still {@code running} and its tenant. */
  void failInterrupted();
}
