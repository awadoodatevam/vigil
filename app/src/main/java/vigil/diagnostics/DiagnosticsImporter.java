package vigil.diagnostics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticsImport;
import vigil.diagnostics.api.DiagnosticsPart;
import vigil.diagnostics.api.DiagnosticsPart.DefinitionPart;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 12: the diagnostic definitions (DM-E-53, DD-F-04), checked as a whole
 * (DM-R-57; P-18 spec Section 6.8). The operation a definition names is checked by the key check of
 * part 12 against part 4. It writes no audit event and publishes no event.
 */
@Service
class DiagnosticsImporter implements DiagnosticsImport {

  static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final int NAME_MAX = 100;
  private static final int TEMPLATE_MAX = 200;

  private final DiagnosticRecords records;
  private final TransactionTemplate tx;
  private final Clock clock;

  DiagnosticsImporter(
      DiagnosticRecords records, PlatformTransactionManager transactionManager, Clock clock) {
    this.records = records;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Map<String, UUID> importPart(
      TenantId tenantId, DiagnosticsPart part, Instant importStartedAt, Map<String, UUID> handles) {
    Diagnostics.require(tenantId, part, importStartedAt, handles);
    Diagnostics.require(part.definitions());
    Diagnostics.require(part.definitions().toArray());
    return tx.execute(
        status -> {
          List<String> problems = problems(tenantId, part);
          if (!problems.isEmpty()) {
            throw new VigilError(
                VigilError.Code.INVALID, "Part diagnostics refused.", Map.of("problems", problems));
          }
          Instant now = Timestamps.now(clock);
          for (DefinitionPart definition : part.definitions()) {
            records.insertDefinition(
                tenantId,
                new DiagnosticDefinitionView(
                    definition.diagnosticKey(),
                    definition.name(),
                    definition.operationKey(),
                    DiagnosticRecords.JSON.writeValueAsString(definition.parameterValues()),
                    definition.summaryTemplate(),
                    true),
                now);
          }
          return Map.of();
        });
  }

  private List<String> problems(TenantId tenantId, DiagnosticsPart part) {
    if (records.hasDefinitions(tenantId)) {
      return List.of("diagnostic records already exist");
    }
    List<String> problems = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < part.definitions().size(); i++) {
      String at = "diagnostic_definitions[" + i + "]";
      DefinitionPart definition = part.definitions().get(i);
      if (!isKey(definition.diagnosticKey())) {
        problems.add(at + ".diagnostic_key: not a key");
      } else if (!keys.add(definition.diagnosticKey())) {
        problems.add(at + ".diagnostic_key: used more than once");
      }
      if (!lengthBetween(definition.name(), NAME_MAX)) {
        problems.add(at + ".name: 1 to 100 characters");
      }
      if (!isKey(definition.operationKey())) {
        problems.add(at + ".operation_key: not a key");
      }
      if (definition.parameterValues() == null || !definition.parameterValues().isObject()) {
        problems.add(at + ".parameter_values: not a mapping");
      }
      if (!lengthBetween(definition.summaryTemplate(), TEMPLATE_MAX)) {
        problems.add(at + ".summary_template: 1 to 200 characters");
      }
    }
    return problems;
  }

  private static boolean isKey(String value) {
    return value != null && KEY.matcher(value).matches();
  }

  private static boolean lengthBetween(String value, int max) {
    if (value == null) {
      return false;
    }
    int length = value.codePointCount(0, value.length());
    return length >= 1 && length <= max;
  }
}
