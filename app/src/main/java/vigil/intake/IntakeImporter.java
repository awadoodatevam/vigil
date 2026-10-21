package vigil.intake;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.CredentialPurpose;
import vigil.connectors.api.CredentialReferenceView;
import vigil.intake.api.Health;
import vigil.intake.api.IntakeImport;
import vigil.intake.api.IntakePart;
import vigil.intake.api.IntakePart.AlertTypePart;
import vigil.intake.api.IntakePart.MappingPart;
import vigil.intake.api.IntakePart.SourcePart;
import vigil.intake.api.MonitoringKind;
import vigil.intake.api.SourceView;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 6: monitoring sources, alert type definitions and mappings (AR-B-11,
 * DD-F-03), checked by the rules of DM-E-15 to DM-E-17 (DM-R-57).
 */
@Service
class IntakeImporter implements IntakeImport {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,9}$");
  private static final Set<String> OPERATORS = Set.of("equals", "starts_with", "contains");
  private static final int NAME_MAX = 100;
  private static final int FIELD_MAX = 200;
  private static final int VALUE_MAX = 200;
  private static final String SETUP = "vigil-setup";
  private static final String SOURCES = "monitoring_sources: ";
  private static final String TYPES = "alert_types: ";
  private static final String MAPPINGS = "alert_type_mappings: ";

  private final IntakeRecords records;
  private final ConnectorService connectors;
  private final SourceTests tests;
  private final Clock clock;

  IntakeImporter(
      IntakeRecords records, ConnectorService connectors, SourceTests tests, Clock clock) {
    this.records = records;
    this.connectors = connectors;
    this.tests = tests;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId, IntakePart part, Instant importStartedAt, Map<String, UUID> handles) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId: required");
    }
    checkShape(part);
    Map<String, UUID> earlier = handles == null ? Map.of() : handles;
    List<String> problems = problems(tenantId, part, earlier);
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID, "Part intake refused.", Map.of("problems", problems));
    }
    store(tenantId, part, earlier);
    return Map.of();
  }

  @Override
  public void startFirstTests(TenantId tenantId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId: required");
    }
    for (IntakeRecords.Stored source : records.sources(tenantId, true)) {
      SourceView view = source.view();
      Thread.ofVirtual()
          .name("intake-first-test-" + view.sourceKey())
          .start(() -> tests.testLogged(tenantId, view));
    }
  }

  private static void checkShape(IntakePart part) {
    if (part == null) {
      throw new IllegalArgumentException("part: required");
    }
    for (List<?> list : Arrays.asList(part.sources(), part.alertTypes(), part.mappings())) {
      if (list == null || list.stream().anyMatch(item -> item == null)) {
        throw new IllegalArgumentException("part: lists without null entries");
      }
    }
  }

  /** Every problem of the part, in the order of P-13 spec Section 6.5. */
  private List<String> problems(TenantId tenantId, IntakePart part, Map<String, UUID> handles) {
    Set<String> problems = new LinkedHashSet<>();
    if (records.hasMonitoringRecords(tenantId)) {
      problems.add("monitoring records already exist");
    }
    sourceProblems(part.sources(), problems);
    referenceProblems(tenantId, part.sources(), handles, problems);
    durationProblems(part.sources(), problems);
    alertTypeProblems(part.alertTypes(), problems);
    mappingProblems(part, problems);
    return List.copyOf(problems);
  }

  /** Rule 2. */
  private static void sourceProblems(List<SourcePart> sources, Set<String> problems) {
    Set<String> keys = new HashSet<>();
    for (SourcePart source : sources) {
      String k = text(source.sourceKey());
      if (!lengthBetween(source.name(), NAME_MAX)) {
        problems.add(SOURCES + "name of " + k + ": 1 to 100 characters");
      }
      if (source.sourceKey() == null || !KEY.matcher(source.sourceKey()).matches()) {
        problems.add(SOURCES + "source_key " + k + ": not a valid key");
      }
      if (source.sourceKey() != null && !keys.add(source.sourceKey())) {
        problems.add(SOURCES + "source_key " + k + " appears more than once");
      }
      if (MonitoringKind.fromKey(source.sourceKind()).isEmpty()) {
        problems.add(
            SOURCES + "source_kind of " + k + ": unknown kind " + text(source.sourceKind()));
      }
    }
  }

  /** Rule 3 (DD-R-07 (1), SL-B-14). */
  private void referenceProblems(
      TenantId tenantId,
      List<SourcePart> sources,
      Map<String, UUID> handles,
      Set<String> problems) {
    Set<String> tokenRefs = new HashSet<>();
    for (SourcePart source : sources) {
      String of = SOURCES + "connector_instance of " + text(source.sourceKey()) + ": ";
      Optional<ConnectorInstanceView> instance =
          handle(handles, source.connectorInstance())
              .flatMap(id -> connectors.instance(tenantId, id));
      Optional<MonitoringKind> kind = MonitoringKind.fromKey(source.sourceKind());
      if (instance.isEmpty()) {
        problems.add(of + "unknown connector instance " + text(source.connectorInstance()));
      } else if (kind.isPresent() && !instance.get().kind().key().equals(kind.get().key())) {
        problems.add(of + "kind must be " + kind.get().key());
      }
      String token = SOURCES + "webhook_token_ref of " + text(source.sourceKey()) + ": ";
      Optional<CredentialReferenceView> reference =
          handle(handles, source.webhookTokenRef())
              .flatMap(id -> connectors.credentialReference(tenantId, id));
      if (reference.isEmpty()) {
        problems.add(token + "unknown credential reference " + text(source.webhookTokenRef()));
      } else if (reference.get().purpose() != CredentialPurpose.WEBHOOK_TOKEN) {
        problems.add(token + "purpose must be webhook_token");
      }
      if (source.webhookTokenRef() != null && !tokenRefs.add(source.webhookTokenRef())) {
        problems.add(
            SOURCES
                + "webhook_token_ref "
                + source.webhookTokenRef()
                + " is used by more than one source");
      }
    }
  }

  /** Rule 4 (DD-R-07 (2), DM-R-41). */
  private static void durationProblems(List<SourcePart> sources, Set<String> problems) {
    for (SourcePart source : sources) {
      String k = text(source.sourceKey());
      List<String> fields =
          List.of("health_check_interval_s", "stale_after_s", "disconnected_after_s");
      List<String> values =
          Arrays.asList(
              source.healthCheckIntervalS(), source.staleAfterS(), source.disconnectedAfterS());
      boolean whole = true;
      for (int i = 0; i < fields.size(); i++) {
        if (!isWhole(values.get(i))) {
          problems.add(SOURCES + fields.get(i) + " of " + k + ": not a whole number of seconds");
          whole = false;
        }
      }
      if (!whole) {
        continue;
      }
      long interval = Long.parseLong(values.get(0));
      long stale = Long.parseLong(values.get(1));
      long disconnected = Long.parseLong(values.get(2));
      if (interval < 1) {
        problems.add(SOURCES + "health_check_interval_s of " + k + ": at least 1");
      }
      if (interval >= stale) {
        problems.add(
            SOURCES + "health_check_interval_s of " + k + ": must be less than stale_after_s");
      }
      if (disconnected <= stale) {
        problems.add(
            SOURCES + "disconnected_after_s of " + k + ": must be greater than stale_after_s");
      }
    }
  }

  /** Rule 5 (BP-A-25). */
  private static void alertTypeProblems(List<AlertTypePart> types, Set<String> problems) {
    Set<String> seen = new HashSet<>();
    for (AlertTypePart type : types) {
      String t = text(type.alertType());
      if (type.alertType() == null || !KEY.matcher(type.alertType()).matches()) {
        problems.add(TYPES + "alert_type " + t + ": not a valid key");
      }
      if (AlertTypeRule.UNCLASSIFIED.equals(type.alertType())) {
        problems.add(TYPES + "alert_type unclassified is reserved");
      }
      if (type.alertType() != null && !seen.add(type.alertType())) {
        problems.add(TYPES + "alert_type " + t + " appears more than once");
      }
      if (!lengthBetween(type.displayName(), NAME_MAX)) {
        problems.add(TYPES + "display_name of " + t + ": 1 to 100 characters");
      }
    }
  }

  /** Rule 6. */
  private static void mappingProblems(IntakePart part, Set<String> problems) {
    Set<String> types = new HashSet<>();
    part.alertTypes().stream()
        .map(AlertTypePart::alertType)
        .filter(type -> type != null)
        .forEach(types::add);
    Set<String> orders = new HashSet<>();
    for (MappingPart mapping : part.mappings()) {
      String m = " of " + text(mapping.sourceKind()) + " " + text(mapping.order()) + ": ";
      Optional<MonitoringKind> kind = MonitoringKind.fromKey(mapping.sourceKind());
      if (kind.isEmpty()) {
        problems.add(MAPPINGS + "source_kind" + m + "unknown kind " + text(mapping.sourceKind()));
      }
      boolean whole = isWhole(mapping.order());
      if (!whole) {
        problems.add(MAPPINGS + "order" + m + "not a whole number");
      }
      if (kind.isPresent()
          && whole
          && !orders.add(kind.get().key() + " " + Long.parseLong(mapping.order()))) {
        problems.add(
            MAPPINGS
                + "order "
                + mapping.order()
                + " appears more than once for "
                + kind.get().key());
      }
      if (kind.isPresent() && !isField(kind.get(), mapping.field())) {
        problems.add(MAPPINGS + "field" + m + "not a field of " + kind.get().key());
      }
      if (!OPERATORS.contains(mapping.operator())) {
        problems.add(MAPPINGS + "operator" + m + "unknown operator " + text(mapping.operator()));
      }
      if (mapping.value() == null || Texts.length(mapping.value()) > VALUE_MAX) {
        problems.add(MAPPINGS + "value" + m + "at most 200 characters");
      }
      if (mapping.alertType() == null || !types.contains(mapping.alertType())) {
        problems.add(
            MAPPINGS + "alert_type" + m + "unknown alert type " + text(mapping.alertType()));
      }
    }
  }

  /** A field of DM-E-17 for the kind ({@code 50-architecture} Sections 12.3 and 12.4). */
  static boolean isField(MonitoringKind kind, String field) {
    if (field == null || Texts.length(field) > FIELD_MAX) {
      return false;
    }
    return switch (kind) {
      case ZABBIX ->
          field.equals("event_name")
              || field.equals("host")
              || field.equals("severity")
              || named(field, "tag.");
      case ALERTMANAGER -> named(field, "label.") || named(field, "annotation.");
    };
  }

  private static boolean named(String field, String prefix) {
    return field.startsWith(prefix) && field.length() > prefix.length();
  }

  private void store(TenantId tenantId, IntakePart part, Map<String, UUID> handles) {
    Instant now = Timestamps.now(clock);
    for (SourcePart source : part.sources()) {
      UUID id =
          records.insertSource(
              tenantId,
              new IntakeRecords.NewSource(
                  source.name(),
                  source.sourceKey(),
                  source.sourceKind(),
                  handles.get(source.connectorInstance()),
                  handles.get(source.webhookTokenRef()),
                  Long.parseLong(source.healthCheckIntervalS()),
                  Long.parseLong(source.staleAfterS()),
                  Long.parseLong(source.disconnectedAfterS())),
              SETUP,
              now);
      records.insertHealthChange(
          tenantId, id, null, Health.DISCONNECTED, now, HealthCause.SOURCE_CREATED);
    }
    records.insertAlertType(tenantId, AlertTypeRule.UNCLASSIFIED, "Unclassified", now);
    for (AlertTypePart type : part.alertTypes()) {
      records.insertAlertType(tenantId, type.alertType(), type.displayName(), now);
    }
    for (MappingPart mapping : part.mappings()) {
      records.insertMapping(
          tenantId,
          new AlertTypeRule.Mapping(
              MonitoringKind.fromKey(mapping.sourceKind()).orElseThrow(),
              Long.parseLong(mapping.order()),
              mapping.field(),
              mapping.operator(),
              mapping.value(),
              mapping.alertType()),
          now);
    }
    records.insertCounter(tenantId);
  }

  private static Optional<UUID> handle(Map<String, UUID> handles, String handle) {
    return handle == null ? Optional.empty() : Optional.ofNullable(handles.get(handle));
  }

  private static boolean isWhole(String value) {
    return value != null && WHOLE.matcher(value).matches();
  }

  private static boolean lengthBetween(String value, int max) {
    if (value == null) {
      return false;
    }
    int length = Texts.length(value);
    return length >= 1 && length <= max;
  }

  private static String text(String value) {
    return String.valueOf(value);
  }
}
