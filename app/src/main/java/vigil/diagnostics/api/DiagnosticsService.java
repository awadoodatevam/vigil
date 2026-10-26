package vigil.diagnostics.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** The reads of vigil-diagnostics (PM-M13). */
public interface DiagnosticsService {

  /** Every definition, by {@code diagnostic_key}. */
  List<DiagnosticDefinitionView> definitions(TenantId tenantId);

  Optional<DiagnosticDefinitionView> definition(TenantId tenantId, String diagnosticKey);

  /** The results of the incident, by {@code collected_at}, then id. */
  List<DiagnosticResultView> results(TenantId tenantId, UUID incidentId);
}
