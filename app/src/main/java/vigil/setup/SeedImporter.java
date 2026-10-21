package vigil.setup;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.audit.api.ConfigurationArea;
import vigil.setup.SeedPartImporter.PartResult;
import vigil.setup.SeedParts.Part;
import vigil.setup.api.PartOutcome;
import vigil.setup.api.PartView;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantState;
import vigil.tenancy.api.TenantView;

/**
 * The seed import (PM-M22-03, AR-B-11): checks the bundle at upload, records the import (DM-E-82)
 * and imports it part by part in DM-R-56 order on its own virtual thread (AR-R-31). No transaction
 * is held around a module's import call; the record is written in short transactions.
 */
@Service
class SeedImporter implements SeedImports {

  private static final Logger LOG = LoggerFactory.getLogger(SeedImporter.class);
  static final Actor SETUP = Actor.module("vigil-setup");
  static final int ERROR_MAX = 500;
  static final String NOT_NEW = "A seed bundle can be imported only into a new tenant.";
  static final String INTERRUPTED = "interrupted by restart";
  static final String UNEXPECTED = "unexpected error";

  private final TenantService tenants;
  private final AuditService audit;
  private final SeedImportRecords records;
  private final SeedPartRegistry registry;
  private final SeedKeyChecks keyChecks;
  private final TransactionTemplate tx;
  private final Clock clock;

  SeedImporter(
      TenantService tenants,
      AuditService audit,
      SeedImportRecords records,
      SeedPartRegistry registry,
      SeedKeyChecks keyChecks,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.tenants = tenants;
    this.audit = audit;
    this.records = records;
    this.registry = registry;
    this.keyChecks = keyChecks;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public SeedImportView start(TenantId tenantId, byte[] bundle) {
    if (tenantId == null || bundle == null) {
      throw new IllegalArgumentException("tenantId and bundle: required");
    }
    TenantView tenant =
        tenants.tenant(tenantId).orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
    if (tenant.state() != TenantState.PROVISIONING || records.exists(tenantId)) {
      throw notNew();
    }
    SeedBundle read = SeedBundleReader.read(bundle);
    UUID id = Ids.newId();
    Instant startedAt = Timestamps.now(clock);
    List<PartView> parts = new ArrayList<>();
    SeedParts.ALL.forEach(
        part -> parts.add(new PartView(part.module(), PartOutcome.NOT_STARTED, Map.of(), null)));
    try {
      tx.executeWithoutResult(
          status ->
              records.insert(
                  id, tenantId, read.bundleName(), read.bundleVersion(), SETUP, startedAt, parts));
    } catch (DuplicateKeyException e) {
      throw notNew();
    }
    SeedImportView view =
        new SeedImportView(
            id,
            tenantId,
            read.bundleName(),
            read.bundleVersion(),
            SeedImportState.RUNNING,
            startedAt,
            null,
            List.copyOf(parts));
    Run run = new Run(id, tenantId, read, startedAt, parts);
    Thread.ofVirtual().name("seed-import-" + id).start(run::execute);
    return view;
  }

  @Override
  public Optional<SeedImportView> seedImport(UUID seedImportId) {
    if (seedImportId == null) {
      throw new IllegalArgumentException("seedImportId: required");
    }
    return records.find(seedImportId);
  }

  @Override
  public void failInterrupted() {
    for (SeedImportView record : records.running()) {
      tx.executeWithoutResult(
          status -> {
            List<PartView> parts = new ArrayList<>(record.parts());
            int failing = registry.lastRegistered();
            for (Part part : SeedParts.ALL) {
              if (registry.importer(part.number()).isPresent()
                  && parts.get(part.number() - 1).outcome() == PartOutcome.NOT_STARTED) {
                failing = part.number();
                break;
              }
            }
            parts.set(failing - 1, failed(parts.get(failing - 1), INTERRUPTED));
            Instant now = Timestamps.now(clock);
            records.update(record.id(), record.tenantId(), SeedImportState.FAILED, parts, now, now);
            boolean provisioning =
                tenants
                    .tenant(record.tenantId())
                    .map(tenant -> tenant.state() == TenantState.PROVISIONING)
                    .orElse(false);
            if (provisioning) {
              tenants.markFailed(record.tenantId());
            }
          });
      LOG.warn("seed import {} failed: interrupted by restart", record.id());
    }
  }

  private static VigilError notNew() {
    return new VigilError(VigilError.Code.REFUSED, NOT_NEW, Map.of("refusal", "tenant_not_new"));
  }

  private static PartView failed(PartView part, String error) {
    return new PartView(part.module(), PartOutcome.FAILED, Map.of(), cut(error));
  }

  /** The first 500 code points of {@code error} (DM-E-82 {@code error} is Text(500)). */
  static String cut(String error) {
    if (error.codePointCount(0, error.length()) <= ERROR_MAX) {
      return error;
    }
    return error.substring(0, error.offsetByCodePoints(0, ERROR_MAX));
  }

  /** The part error of a module's refusal (P-11 spec Section 6.7, step 1). */
  static String errorText(VigilError error) {
    String message = error.message() != null ? error.message() : error.code().wireText();
    if (error.code() == VigilError.Code.INVALID
        && error.details().get("problems") instanceof List<?> problems
        && !problems.isEmpty()) {
      List<String> texts = problems.stream().map(String::valueOf).toList();
      return message + " " + String.join("; ", texts);
    }
    return message;
  }

  /** One accepted import, worked on its own virtual thread. */
  private final class Run {

    private final UUID id;
    private final TenantId tenantId;
    private final SeedBundle bundle;
    private final Instant startedAt;
    private final List<PartView> parts;
    private final Map<String, JsonNode> files = new LinkedHashMap<>();
    private final Map<String, UUID> handles = new LinkedHashMap<>();

    Run(UUID id, TenantId tenantId, SeedBundle bundle, Instant startedAt, List<PartView> parts) {
      this.id = id;
      this.tenantId = tenantId;
      this.bundle = bundle;
      this.startedAt = startedAt;
      this.parts = new ArrayList<>(parts);
    }

    void execute() {
      int current = registry.lastRegistered();
      try {
        for (Part part : SeedParts.ALL) {
          current = part.number();
          if (!importPart(part)) {
            return;
          }
        }
        current = registry.lastRegistered();
        if (!checkKeys()) {
          return;
        }
        succeed();
      } catch (RuntimeException e) {
        LOG.error("seed import {} failed: unexpected error in part {}", id, current, e);
        fail(Map.of(current, UNEXPECTED));
      }
    }

    /** Step 1 for one part; false when the part failed and the import ended. */
    private boolean importPart(Part part) {
      List<String> problems = new ArrayList<>();
      for (String file : SeedParts.filesFirstReadBy(part.number())) {
        problems.addAll(SeedParts.checkFile(file, bundle.texts().get(file), files));
      }
      if (!problems.isEmpty()) {
        failPart(part, "Part " + part.name() + " refused. " + String.join("; ", problems));
        return false;
      }
      Optional<SeedPartImporter> importer = registry.importer(part.number());
      if (importer.isEmpty()) {
        return true;
      }
      PartResult result;
      try {
        result =
            importer
                .get()
                .importPart(
                    tenantId,
                    bundle,
                    Collections.unmodifiableMap(files),
                    startedAt,
                    Collections.unmodifiableMap(new LinkedHashMap<>(handles)));
      } catch (VigilError e) {
        failPart(part, errorText(e));
        return false;
      }
      Map<String, Integer> counts =
          result.counts() == null ? Map.of() : new LinkedHashMap<>(result.counts());
      parts.set(
          part.number() - 1, new PartView(part.module(), PartOutcome.SUCCEEDED, counts, null));
      tx.executeWithoutResult(
          status ->
              records.update(
                  id, tenantId, SeedImportState.RUNNING, parts, Timestamps.now(clock), null));
      Map<String, UUID> returned = result.handles() == null ? Map.of() : result.handles();
      returned.forEach(
          (handle, recordId) -> {
            if (handles.putIfAbsent(handle, recordId) != null) {
              throw new IllegalStateException("handle " + handle + " is already in the map");
            }
          });
      importer.get().afterCommit(tenantId, returned);
      return true;
    }

    /** Step 2: the key checks whose two parts succeeded; false when one found problems. */
    private boolean checkKeys() {
      Map<Integer, List<String>> problems = new TreeMap<>();
      for (SeedKeyCheck check : keyChecks.all()) {
        if (succeeded(check.referringPart()) && succeeded(check.targetPart())) {
          List<String> found =
              check.problems(
                  tenantId,
                  bundle,
                  Collections.unmodifiableMap(files),
                  Collections.unmodifiableMap(handles));
          if (!found.isEmpty()) {
            problems.computeIfAbsent(check.referringPart(), p -> new ArrayList<>()).addAll(found);
          }
        }
      }
      if (problems.isEmpty()) {
        return true;
      }
      Map<Integer, String> errors = new TreeMap<>();
      problems.forEach(
          (part, texts) -> {
            String error = "Key check failed: " + String.join("; ", texts);
            errors.put(part, error);
            LOG.warn(
                "seed import {} part {} ({}) failed: {}",
                id,
                part,
                SeedParts.part(part).module(),
                error);
          });
      fail(errors);
      return false;
    }

    private boolean succeeded(int part) {
      return part >= 1
          && part <= parts.size()
          && parts.get(part - 1).outcome() == PartOutcome.SUCCEEDED;
    }

    private void failPart(Part part, String error) {
      LOG.warn(
          "seed import {} part {} ({}) failed: {}", id, part.number(), part.module(), cut(error));
      fail(Map.of(part.number(), error));
    }

    /** Step 3: the failing parts, the record and the tenant become {@code failed}. */
    private void fail(Map<Integer, String> errors) {
      errors.forEach((part, error) -> parts.set(part - 1, failed(parts.get(part - 1), error)));
      try {
        tx.executeWithoutResult(
            status -> {
              Instant now = Timestamps.now(clock);
              records.update(id, tenantId, SeedImportState.FAILED, parts, now, now);
              tenants.markFailed(tenantId);
            });
      } catch (RuntimeException e) {
        LOG.error("seed import {} could not be marked failed", id, e);
      }
    }

    /** Step 4: the record {@code succeeded}, the one audit event, the tenant {@code ready}. */
    private void succeed() {
      tx.executeWithoutResult(
          status -> {
            Instant now = Timestamps.now(clock);
            records.update(id, tenantId, SeedImportState.SUCCEEDED, parts, now, now);
            audit.append(
                new AuditEntry(
                    tenantId,
                    now,
                    SETUP,
                    AuditEventType.CONFIGURATION_CHANGED,
                    "SeedImport",
                    id,
                    null,
                    "Seed bundle "
                        + bundle.bundleName()
                        + " "
                        + bundle.bundleVersion()
                        + " imported.",
                    details()));
            tenants.markReady(tenantId);
          });
    }

    /** DM-C-07: area, no before, the bundle and the outcome and counts of every part. */
    private String details() {
      ObjectNode details = SeedImportRecords.JSON.createObjectNode();
      details.put("area", ConfigurationArea.SEED_IMPORT.key());
      details.putNull("before");
      ObjectNode after = details.putObject("after");
      after.put("bundle_name", bundle.bundleName());
      after.put("bundle_version", bundle.bundleVersion());
      ArrayNode list = after.putArray("parts");
      for (PartView part : parts) {
        ObjectNode entry = list.addObject();
        entry.put("module", part.module());
        entry.put("outcome", part.outcome().key());
        ObjectNode counts = entry.putObject("counts");
        part.counts().forEach(counts::put);
      }
      return SeedImportRecords.JSON.writeValueAsString(details);
    }
  }
}
