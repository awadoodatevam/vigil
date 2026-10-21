package vigil.servicemap;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.audit.api.ConfigurationArea;
import vigil.servicemap.api.AffectedService;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.Counts;
import vigil.servicemap.api.GenerationView;
import vigil.servicemap.api.IdentityKey;
import vigil.servicemap.api.ImportResult;
import vigil.servicemap.api.Match;
import vigil.servicemap.api.Relation;
import vigil.servicemap.api.RelationKind;
import vigil.servicemap.api.ServiceMapImport;
import vigil.servicemap.api.ServiceMapImported;
import vigil.servicemap.api.ServiceMapPart;
import vigil.servicemap.api.ServiceMapService;
import vigil.servicemap.api.ServiceView;
import vigil.servicemap.api.SourceKind;
import vigil.shared.Actor;
import vigil.shared.CanonicalJson;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/** The service map (PM-M07): the questions of the current generation and its imports. */
@Service
class ServiceMaps implements ServiceMapService, ServiceMapImport {

  private static final Logger LOG = LoggerFactory.getLogger(ServiceMaps.class);
  private static final int FILE_NAME_MAX = 255;
  private static final Actor SETUP = Actor.module("vigil-setup");

  private final ServiceMapRecords records;
  private final ServiceMapGraphs graphs;
  private final JdbcClient jdbc;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  ServiceMaps(
      ServiceMapRecords records,
      ServiceMapGraphs graphs,
      JdbcClient jdbc,
      AuditService audit,
      ApplicationEventPublisher events,
      Clock clock) {
    this.records = records;
    this.graphs = graphs;
    this.jdbc = jdbc;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  @Override
  public Optional<GenerationView> currentGeneration(TenantId tenantId) {
    required(tenantId, "tenantId");
    return records.current(tenantId).map(ServiceMapRecords.Stored::view);
  }

  @Override
  public Match match(TenantId tenantId, SourceKind sourceKind, List<IdentityKey> pairs) {
    required(sourceKind, "sourceKind");
    required(pairs, "pairs");
    return graphs
        .current(tenantId)
        .map(graph -> graph.match(sourceKind, pairs))
        .orElse(new Match(0, null));
  }

  @Override
  public Relation relation(TenantId tenantId, String a, String b) {
    required(a, "a");
    required(b, "b");
    if (a.equals(b)) {
      return new Relation(RelationKind.SAME_COMPONENT, List.of(a));
    }
    return ask(tenantId, graph -> graph.relation(a, b), new Relation(RelationKind.NONE, List.of()));
  }

  @Override
  public List<String> dependents(TenantId tenantId, String componentKey) {
    required(componentKey, "componentKey");
    return ask(tenantId, graph -> graph.dependents(componentKey), List.of());
  }

  @Override
  public List<AffectedService> affectedServices(
      TenantId tenantId, Collection<String> componentKeys) {
    required(componentKeys, "componentKeys");
    return ask(tenantId, graph -> graph.affectedServices(componentKeys), List.of());
  }

  @Override
  public Optional<ComponentView> component(TenantId tenantId, String componentKey) {
    required(componentKey, "componentKey");
    return ask(tenantId, graph -> graph.component(componentKey), Optional.empty());
  }

  @Override
  public Optional<ServiceView> service(TenantId tenantId, String serviceKey) {
    required(serviceKey, "serviceKey");
    return ask(tenantId, graph -> graph.service(serviceKey), Optional.empty());
  }

  @Override
  public Optional<String> target(TenantId tenantId, String componentKey) {
    required(componentKey, "componentKey");
    return ask(tenantId, graph -> graph.target(componentKey), Optional.empty());
  }

  @Override
  public List<ServiceView> services(TenantId tenantId) {
    return ask(tenantId, ServiceMapGraph::services, List.of());
  }

  @Override
  public List<ComponentView> components(TenantId tenantId) {
    return ask(tenantId, ServiceMapGraph::components, List.of());
  }

  private <T> T ask(TenantId tenantId, Function<ServiceMapGraph, T> question, T none) {
    return graphs.current(tenantId).map(question).orElse(none);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public ImportResult importFile(
      TenantId tenantId, String fileName, byte[] content, Actor importedBy) {
    required(tenantId, "tenantId");
    required(fileName, "fileName");
    required(content, "content");
    required(importedBy, "importedBy");
    int length = fileName.codePointCount(0, fileName.length());
    if (length < 1 || length > FILE_NAME_MAX) {
      throw new VigilError(VigilError.Code.BAD_REQUEST, "file name: 1 to 255 characters");
    }
    ServiceMapFile.Read read = ServiceMapFile.read(content);
    if (!read.problems().isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID,
          "The file was not imported.",
          Map.of("problems", read.problems()));
    }
    lock(tenantId);
    Optional<ServiceMapRecords.Stored> before = records.current(tenantId);
    long generation = before.map(stored -> stored.view().generation() + 1).orElse(1L);
    Instant now = Timestamps.now(clock);
    UUID id = store(tenantId, generation, fileName, content, importedBy, now, read.content());
    Counts counts = read.content().counts();
    ObjectNode details = ServiceMapRecords.JSON.createObjectNode();
    details.put("area", ConfigurationArea.SERVICE_MAP.key());
    if (before.isPresent()) {
      GenerationView previous = before.get().view();
      details.set("before", summary(previous.generation(), previous.fileName(), previous.counts()));
    } else {
      details.putNull("before");
    }
    details.set("after", summary(generation, fileName, counts));
    audit.append(
        new AuditEntry(
            tenantId,
            now,
            importedBy,
            AuditEventType.CONFIGURATION_CHANGED,
            "ServiceMapGeneration",
            id,
            null,
            "Service map imported as generation "
                + generation
                + " ("
                + count(counts.services(), "service", "services")
                + ", "
                + count(counts.components(), "component", "components")
                + ", "
                + count(counts.identityKeys(), "identity key", "identity keys")
                + ", "
                + count(counts.dependencies(), "dependency", "dependencies")
                + ").",
            ServiceMapRecords.JSON.writeValueAsString(details)));
    events.publishEvent(new ServiceMapImported(tenantId, generation));
    return new ImportResult(generation, counts);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId, ServiceMapPart part, Instant importStartedAt, Map<String, UUID> handles) {
    required(tenantId, "tenantId");
    required(part, "part");
    required(part.fileName(), "part.fileName");
    required(part.content(), "part.content");
    lock(tenantId);
    List<String> problems = new ArrayList<>();
    if (records.currentNumber(tenantId).isPresent()) {
      problems.add("service map already exists");
    }
    ServiceMapFile.Read read = ServiceMapFile.read(part.content());
    problems.addAll(read.problems());
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID, "Part service map refused.", Map.of("problems", problems));
    }
    store(
        tenantId, 1, part.fileName(), part.content(), SETUP, Timestamps.now(clock), read.content());
    return Map.of();
  }

  private UUID store(
      TenantId tenantId,
      long generation,
      String fileName,
      byte[] content,
      Actor importedBy,
      Instant now,
      ServiceMapFile.Content file) {
    UUID id =
        records.insert(
            tenantId,
            generation,
            fileName,
            CanonicalJson.sha256Hex(content),
            importedBy,
            Timestamp.from(now),
            file);
    Counts counts = file.counts();
    LOG.info(
        "service map imported: tenant {} generation {} ({} services, {} components, {} identity"
            + " keys, {} dependencies)",
        tenantId.value(),
        generation,
        counts.services(),
        counts.components(),
        counts.identityKeys(),
        counts.dependencies());
    return id;
  }

  /** One import per tenant at a time. */
  private void lock(TenantId tenantId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtext('servicemap.generation'),"
                + " hashtext(:tenantId))::text")
        .param("tenantId", tenantId.value().toString())
        .query(String.class)
        .single();
  }

  private static ObjectNode summary(long generation, String fileName, Counts counts) {
    ObjectNode summary = ServiceMapRecords.JSON.createObjectNode();
    summary.put("generation", generation);
    summary.put("file_name", fileName);
    summary.set("counts", ServiceMapRecords.countsJson(counts));
    return summary;
  }

  /** DC-TX-00 Counts: the singular noun for 1. */
  private static String count(int n, String singular, String plural) {
    return n + " " + (n == 1 ? singular : plural);
  }

  private static void required(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + ": required");
    }
  }
}
