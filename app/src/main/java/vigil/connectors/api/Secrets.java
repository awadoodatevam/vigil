package vigil.connectors.api;

import java.util.UUID;
import vigil.shared.TenantId;

/**
 * The secret store (AR-S-04): secret files read at each use. Not external calls. Each operation has
 * one caller module (AR-Q-09).
 */
public interface Secrets {

  /**
   * Whether {@code presented} equals the secret of a {@code webhook_token} credential reference,
   * compared in constant time (AR-S-05). Caller: vigil-intake.
   */
  boolean verify(TenantId tenantId, UUID credentialRefId, String presented);

  /** The secret of a target credential reference. Caller: vigil-runner. */
  String read(TenantId tenantId, UUID credentialRefId);

  /** The secret {@code seed-password-{username}} (AR-S-06). Caller: vigil-setup. */
  String readSeedPassword(String username);

  /**
   * Whether {@code presented} equals the secret {@code installation-token}, compared in constant
   * time (AR-S-07, BP-A-10). Caller: vigil-api.
   */
  boolean verifyInstallationToken(String presented);
}
