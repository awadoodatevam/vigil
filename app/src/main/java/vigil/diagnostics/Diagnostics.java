package vigil.diagnostics;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticResultView;
import vigil.diagnostics.api.DiagnosticsService;
import vigil.shared.TenantId;

/** The reads of vigil-diagnostics (PM-M13). */
@Service
class Diagnostics implements DiagnosticsService {

  private final DiagnosticRecords records;

  Diagnostics(DiagnosticRecords records) {
    this.records = records;
  }

  @Override
  public List<DiagnosticDefinitionView> definitions(TenantId tenantId) {
    require(tenantId);
    return List.copyOf(records.definitions(tenantId));
  }

  @Override
  public Optional<DiagnosticDefinitionView> definition(TenantId tenantId, String diagnosticKey) {
    require(tenantId, diagnosticKey);
    return records.definition(tenantId, diagnosticKey);
  }

  @Override
  public List<DiagnosticResultView> results(TenantId tenantId, UUID incidentId) {
    require(tenantId, incidentId);
    return List.copyOf(records.results(tenantId, incidentId));
  }

  static void require(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
