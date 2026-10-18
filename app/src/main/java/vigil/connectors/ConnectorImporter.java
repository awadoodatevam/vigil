package vigil.connectors;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.ConnectorInstancePart;
import vigil.connectors.api.ConnectorsPart.CredentialReferencePart;
import vigil.connectors.api.CredentialPurpose;
import vigil.connectors.api.SettingType;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/** The seed import of part 3: credential references and connector instances (AR-B-11, DD-F-07). */
@Service
class ConnectorImporter implements ConnectorImport {

  private static final int NAME_MAX = 100;
  private static final int ADDRESS_MAX = 500;
  private static final Pattern LOCATOR = Pattern.compile("^secret:[a-z0-9-]{1,64}$");
  private static final Actor SETUP = Actor.module("vigil-setup");
  private static final String REFS = "credential_references: ";
  private static final String INSTANCES = "connector_instances: ";

  private final JdbcClient jdbc;
  private final Clock clock;

  ConnectorImporter(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId, ConnectorsPart part, Instant importStartedAt, Map<String, UUID> handles) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId: required");
    }
    checkShape(part);
    List<String> problems = problems(tenantId, part, handles == null ? Map.of() : handles);
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID, "Part connectors refused.", Map.of("problems", problems));
    }
    return Collections.unmodifiableMap(store(tenantId, part));
  }

  private static void checkShape(ConnectorsPart part) {
    if (part == null) {
      throw new IllegalArgumentException("part: required");
    }
    for (List<?> list : Arrays.asList(part.credentialReferences(), part.connectorInstances())) {
      if (list == null || list.stream().anyMatch(item -> item == null)) {
        throw new IllegalArgumentException("part: lists without null entries");
      }
    }
  }

  /** Every problem of the part, in the order of P-10 spec Section 6.5. */
  private List<String> problems(TenantId tenantId, ConnectorsPart part, Map<String, UUID> earlier) {
    List<CredentialReferencePart> refs = part.credentialReferences();
    List<ConnectorInstancePart> instances = part.connectorInstances();
    List<String> problems = new ArrayList<>();
    if (hasRecords(tenantId)) {
      problems.add("connector records already exist");
    }
    if (refs.stream().anyMatch(ref -> isEmpty(ref.handle()))) {
      problems.add(REFS + "a handle is empty");
    }
    if (instances.stream().anyMatch(instance -> isEmpty(instance.handle()))) {
      problems.add(INSTANCES + "a handle is empty");
    }
    List<String> allHandles = new ArrayList<>();
    refs.forEach(ref -> allHandles.add(ref.handle()));
    instances.forEach(instance -> allHandles.add(instance.handle()));
    List<String> handles = allHandles.stream().filter(h -> !isEmpty(h)).toList();
    Set<String> seen = new LinkedHashSet<>();
    Set<String> repeated = new LinkedHashSet<>();
    for (String handle : handles) {
      if (!seen.add(handle)) {
        repeated.add("handle " + handle + " appears more than once");
      }
    }
    problems.addAll(repeated);
    Set<String> used = new LinkedHashSet<>();
    handles.stream()
        .filter(earlier::containsKey)
        .forEach(h -> used.add("handle " + h + " is already used by an earlier part"));
    problems.addAll(used);
    Set<String> purposes = new LinkedHashSet<>();
    Set<String> locators = new LinkedHashSet<>();
    for (CredentialReferencePart ref : refs) {
      if (CredentialPurpose.fromKey(ref.purpose()).isEmpty()) {
        purposes.add(
            REFS + "purpose of " + text(ref.handle()) + ": unknown purpose " + text(ref.purpose()));
      }
      if (ref.locator() == null || !LOCATOR.matcher(ref.locator()).matches()) {
        locators.add(REFS + "locator of " + text(ref.handle()) + ": not of the form secret:{name}");
      }
    }
    problems.addAll(purposes);
    problems.addAll(locators);
    Set<String> names = new LinkedHashSet<>();
    Set<String> kinds = new LinkedHashSet<>();
    Set<String> addresses = new LinkedHashSet<>();
    Set<String> settings = new LinkedHashSet<>();
    Set<String> credentials = new LinkedHashSet<>();
    for (ConnectorInstancePart instance : instances) {
      String of = text(instance.handle());
      if (!lengthBetween(instance.name(), NAME_MAX)) {
        names.add(INSTANCES + "name of " + of + ": 1 to 100 characters");
      }
      Optional<ConnectorKind> kind = ConnectorKind.fromKey(instance.kind());
      if (kind.isEmpty()) {
        kinds.add(INSTANCES + "kind of " + of + ": unknown kind " + text(instance.kind()));
      }
      if (!lengthBetween(instance.address(), ADDRESS_MAX)) {
        addresses.add(INSTANCES + "address of " + of + ": 1 to 500 characters");
      }
      if (kind.isPresent()) {
        settingsProblems(kind.get(), instance.settings())
            .forEach(p -> settings.add(INSTANCES + "settings of " + of + ": " + p));
        credentialProblem(kind.get(), instance.credentialRef(), refs)
            .ifPresent(p -> credentials.add(INSTANCES + "credential_ref of " + of + ": " + p));
      }
    }
    problems.addAll(names);
    problems.addAll(kinds);
    problems.addAll(addresses);
    problems.addAll(settings);
    problems.addAll(credentials);
    return problems;
  }

  /** The problems of an instance's settings against its kind's structure (DM-E-09). */
  private static List<String> settingsProblems(ConnectorKind kind, JsonNode settings) {
    if (settings == null || !settings.isObject()) {
      return List.of("not an object");
    }
    List<String> problems = new ArrayList<>();
    kind.settingsSchema()
        .forEach(
            (key, type) -> {
              JsonNode value = settings.get(key);
              if (value == null) {
                problems.add("missing " + key);
              } else if (!hasType(value, type)) {
                problems.add(key + " is not " + typeName(type));
              }
            });
    for (String key : settings.propertyNames()) {
      if (!kind.settingsSchema().containsKey(key)) {
        problems.add("unknown setting " + key);
      }
    }
    return problems;
  }

  private static boolean hasType(JsonNode value, SettingType type) {
    return switch (type) {
      case STRING -> value.isString();
      case INTEGER -> value.isIntegralNumber() && value.canConvertToLong();
      case BOOLEAN -> value.isBoolean();
    };
  }

  private static String typeName(SettingType type) {
    return switch (type) {
      case STRING -> "a string";
      case INTEGER -> "an integer";
      case BOOLEAN -> "a boolean";
    };
  }

  /** The problem of an instance's credential reference for its kind, if any (AR §12.2). */
  private static Optional<String> credentialProblem(
      ConnectorKind kind, String credentialRef, List<CredentialReferencePart> refs) {
    String ref = isEmpty(credentialRef) ? null : credentialRef;
    Optional<CredentialPurpose> required = kind.instanceCredential();
    if (required.isPresent() && ref == null) {
      return Optional.of("required for kind " + kind.key());
    }
    if (required.isEmpty()) {
      return ref == null ? Optional.empty() : Optional.of("not allowed for kind " + kind.key());
    }
    Optional<CredentialReferencePart> target =
        refs.stream().filter(candidate -> ref.equals(candidate.handle())).findFirst();
    if (target.isEmpty()) {
      return Optional.of("unknown credential reference " + ref);
    }
    Optional<CredentialPurpose> purpose = CredentialPurpose.fromKey(target.get().purpose());
    if (purpose.isPresent() && purpose.get() != required.get()) {
      return Optional.of("purpose must be " + required.get().key());
    }
    return Optional.empty();
  }

  private Map<String, UUID> store(TenantId tenantId, ConnectorsPart part) {
    Timestamp now = Timestamp.from(Timestamps.now(clock));
    Map<String, UUID> ids = new LinkedHashMap<>();
    for (CredentialReferencePart ref : part.credentialReferences()) {
      UUID id = Ids.newId();
      ids.put(ref.handle(), id);
      jdbc.sql(
              "INSERT INTO connectors.credential_reference (id, tenant_id, created_at, updated_at,"
                  + " purpose, locator) VALUES (:id, :tenantId, :now, :now, :purpose, :locator)")
          .param("id", id)
          .param("tenantId", tenantId.value())
          .param("now", now)
          .param("purpose", ref.purpose())
          .param("locator", ref.locator())
          .update();
    }
    Map<String, UUID> refIds = new LinkedHashMap<>(ids);
    for (ConnectorInstancePart instance : part.connectorInstances()) {
      UUID id = Ids.newId();
      ids.put(instance.handle(), id);
      String ref = isEmpty(instance.credentialRef()) ? null : instance.credentialRef();
      jdbc.sql(
              "INSERT INTO connectors.connector_instance (id, tenant_id, created_at, updated_at,"
                  + " name, kind, address, settings, credential_ref_id, active, updated_by_kind,"
                  + " updated_by_user_id, updated_by_module) VALUES (:id, :tenantId, :now, :now,"
                  + " :name, :kind, :address, CAST(:settings AS jsonb), :credentialRefId, true,"
                  + " 'module', NULL, :module)")
          .param("id", id)
          .param("tenantId", tenantId.value())
          .param("now", now)
          .param("name", instance.name())
          .param("kind", instance.kind())
          .param("address", instance.address())
          .param("settings", JsonBodies.JSON.writeValueAsString(instance.settings()))
          .param("credentialRefId", ref == null ? null : refIds.get(ref))
          .param("module", SETUP.module())
          .update();
    }
    return ids;
  }

  private boolean hasRecords(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM connectors.credential_reference"
                + " WHERE tenant_id = :tenantId) OR EXISTS (SELECT 1 FROM"
                + " connectors.connector_instance WHERE tenant_id = :tenantId)")
        .param("tenantId", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  private static boolean isEmpty(String value) {
    return value == null || value.isEmpty();
  }

  private static boolean lengthBetween(String value, int max) {
    if (value == null) {
      return false;
    }
    int length = value.codePointCount(0, value.length());
    return length >= 1 && length <= max;
  }

  private static String text(String value) {
    return String.valueOf(value);
  }
}
