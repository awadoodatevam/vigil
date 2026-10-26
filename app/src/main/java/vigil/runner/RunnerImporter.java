package vigil.runner;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.CredentialReferenceView;
import vigil.runner.api.GrantView;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunnerImport;
import vigil.runner.api.RunnerPart;
import vigil.runner.api.RunnerPart.GrantPart;
import vigil.runner.api.RunnerPart.OperationPart;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 4: operations and target access grants (DM-E-12, DM-E-13, DD-F-04),
 * checked as a whole (DM-R-57; P-18 spec Section 6.5). It writes no audit event and publishes no
 * event.
 */
@Service
class RunnerImporter implements RunnerImport {

  static final List<String> PARSERS =
      List.of("df_row", "du_top", "count_bytes", "single_number", "removed_files");
  private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
  private static final int NAME_MAX = 100;
  private static final int TEMPLATE_MAX = 2000;
  private static final int TIMEOUT_MAX = 3600;

  private final RunnerRecords records;
  private final ConnectorService connectors;
  private final TransactionTemplate tx;
  private final Clock clock;

  RunnerImporter(
      RunnerRecords records,
      ConnectorService connectors,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.connectors = connectors;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Map<String, UUID> importPart(
      TenantId tenantId, RunnerPart part, Instant importStartedAt, Map<String, UUID> handles) {
    Runner.require(tenantId, part, importStartedAt, handles);
    Runner.require(part.operations(), part.grants());
    Runner.require(part.operations().toArray());
    Runner.require(part.grants().toArray());
    return tx.execute(
        status -> {
          List<String> problems = problems(tenantId, part, handles);
          if (!problems.isEmpty()) {
            throw new VigilError(
                VigilError.Code.INVALID, "Part runner refused.", Map.of("problems", problems));
          }
          store(tenantId, part, handles);
          return Map.of();
        });
  }

  private List<String> problems(TenantId tenantId, RunnerPart part, Map<String, UUID> handles) {
    if (records.hasRecords(tenantId)) {
      return List.of("runner records already exist");
    }
    List<String> problems = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < part.operations().size(); i++) {
      operationProblems("operations[" + i + "]", part.operations().get(i), keys, problems);
    }
    Set<String> grants = new HashSet<>();
    for (int i = 0; i < part.grants().size(); i++) {
      grantProblems(
          tenantId,
          "target_access_grants[" + i + "]",
          part.grants().get(i),
          handles,
          grants,
          problems);
    }
    return problems;
  }

  private static void operationProblems(
      String at, OperationPart operation, Set<String> keys, List<String> problems) {
    if (!isKey(operation.operationKey())) {
      problems.add(at + ".operation_key: not a key");
    } else if (!keys.add(operation.operationKey())) {
      problems.add(at + ".operation_key: used more than once");
    }
    if (!lengthBetween(operation.name(), NAME_MAX)) {
      problems.add(at + ".name: 1 to 100 characters");
    }
    if (PermissionKind.fromKey(operation.permissionKind()).isEmpty()) {
      problems.add(at + ".permission_kind: one of diagnostic, action");
    }
    if (!lengthBetween(operation.commandTemplate(), TEMPLATE_MAX)) {
      problems.add(at + ".command_template: 1 to 2000 characters");
    }
    JsonNode parameters = operation.parameters();
    if (parameters == null || !parameters.isArray()) {
      problems.add(at + ".parameters: not a list");
    } else {
      problems.addAll(Parameters.definitionProblems(at + ".parameters", parameters));
    }
    if (!PARSERS.contains(operation.outputParser())) {
      problems.add(at + ".output_parser: one of " + String.join(", ", PARSERS));
    }
    if (timeoutSeconds(operation.timeout()) == null) {
      problems.add(at + ".timeout_s: 1 to 3600 seconds");
    }
  }

  private void grantProblems(
      TenantId tenantId,
      String at,
      GrantPart grant,
      Map<String, UUID> handles,
      Set<String> seen,
      List<String> problems) {
    boolean targetValid = isKey(grant.targetKey());
    if (!targetValid) {
      problems.add(at + ".target_key: not a key");
    }
    Optional<ConnectorInstanceView> instance =
        handle(handles, grant.connectorInstance()).flatMap(id -> connectors.instance(tenantId, id));
    if (instance.isEmpty()) {
      problems.add(at + ".connector_instance: not a connector instance of part 3");
    } else if (instance.get().kind() != ConnectorKind.LINUX_SERVER) {
      problems.add(at + ".connector_instance: not a linux_server instance");
    }
    Optional<PermissionKind> kind = PermissionKind.fromKey(grant.permissionKind());
    if (kind.isEmpty()) {
      problems.add(at + ".permission_kind: one of diagnostic, action");
    }
    Optional<CredentialReferenceView> credential =
        handle(handles, grant.credentialRef())
            .flatMap(id -> connectors.credentialReference(tenantId, id));
    if (credential.isEmpty()) {
      problems.add(at + ".credential_ref: not a credential reference of part 3");
    } else if (kind.isPresent()
        && !credential.get().purpose().key().equals("target_" + kind.get().key())) {
      problems.add(at + ".credential_ref: purpose must be target_" + kind.get().key());
    }
    if (targetValid && kind.isPresent() && !seen.add(grant.targetKey() + " " + kind.get().key())) {
      problems.add(at + ": a second grant for " + grant.targetKey() + " " + kind.get().key());
    }
  }

  private void store(TenantId tenantId, RunnerPart part, Map<String, UUID> handles) {
    Instant now = Timestamps.now(clock);
    for (OperationPart operation : part.operations()) {
      records.insertOperation(
          tenantId,
          Ids.newId(),
          new OperationView(
              operation.operationKey(),
              operation.name(),
              PermissionKind.fromKey(operation.permissionKind()).orElseThrow(),
              operation.commandTemplate(),
              Parameters.JSON.writeValueAsString(operation.parameters()),
              operation.outputParser(),
              timeoutSeconds(operation.timeout()),
              true),
          now);
    }
    for (GrantPart grant : part.grants()) {
      records.insertGrant(
          tenantId,
          new GrantView(
              Ids.newId(),
              grant.targetKey(),
              handles.get(grant.connectorInstance()),
              PermissionKind.fromKey(grant.permissionKind()).orElseThrow(),
              handles.get(grant.credentialRef()),
              true),
          now);
    }
  }

  /** The seconds of a written {@code timeout_s}: digits only, 1 to 3600; otherwise null. */
  private static Long timeoutSeconds(String timeout) {
    if (timeout == null || !DIGITS.matcher(timeout).matches()) {
      return null;
    }
    String digits = timeout.replaceFirst("^0+(?=.)", "");
    if (digits.length() > 4) {
      return null;
    }
    long seconds = Long.parseLong(digits);
    return seconds >= 1 && seconds <= TIMEOUT_MAX ? seconds : null;
  }

  private static Optional<UUID> handle(Map<String, UUID> handles, String handle) {
    return handle == null ? Optional.empty() : Optional.ofNullable(handles.get(handle));
  }

  private static boolean isKey(String value) {
    return value != null && Parameters.KEY.matcher(value).matches();
  }

  private static boolean lengthBetween(String value, int max) {
    if (value == null) {
      return false;
    }
    int length = value.codePointCount(0, value.length());
    return length >= 1 && length <= max;
  }
}
