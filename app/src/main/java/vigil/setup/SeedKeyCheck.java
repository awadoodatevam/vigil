package vigil.setup;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vigil.shared.TenantId;

/**
 * One cross-module key check of DM-R-60 (PM-M22-04): the keys that a referring part uses and a
 * target part defines. It runs after the last part when both parts succeeded (BP-R-29).
 */
interface SeedKeyCheck {

  int referringPart();

  int targetPart();

  /** The texts of the keys that do not exist; empty when the check passes. */
  List<String> problems(
      TenantId tenantId, SeedBundle bundle, Map<String, JsonNode> files, Map<String, UUID> handles);
}
