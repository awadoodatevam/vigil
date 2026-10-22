package vigil.intake;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ParseResult;
import vigil.connectors.api.ParsedEntry;
import vigil.connectors.api.Secrets;
import vigil.intake.api.AlertReceived;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.AlertStatus;
import vigil.intake.api.AlertTypeView;
import vigil.intake.api.AlertView;
import vigil.intake.api.Health;
import vigil.intake.api.IdentityPair;
import vigil.intake.api.IntakeService;
import vigil.intake.api.MonitoringKind;
import vigil.intake.api.SourceTestResult;
import vigil.intake.api.SourceView;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;
import vigil.shared.VigilError;

/** The requests of other modules to vigil-intake (PM-M06), the webhook among them (AR-R-16). */
@Service
class Intake implements IntakeService {

  private static final Logger LOG = LoggerFactory.getLogger(Intake.class);
  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final int CONTENT_TYPE_MAX = 100;
  private static final int REASON_MAX = 300;
  private static final int ALERT_KEY_MAX = 200;
  private static final int TITLE_MAX = 300;
  private static final int SEVERITY_MAX = 50;
  private static final int PAIR_VALUE_MAX = 200;
  private static final int PAGE_MAX = 100;
  static final String KEY_TOO_LONG = "the alert key is longer than 200 characters";
  static final String HOLDS_NUL = "the entry holds the character U+0000";

  private final IntakeRecords records;
  private final ConnectorService connectors;
  private final Secrets secrets;
  private final SourceHealth health;
  private final SourceTests tests;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate tx;
  private final Clock clock;

  Intake(
      IntakeRecords records,
      ConnectorService connectors,
      Secrets secrets,
      SourceHealth health,
      SourceTests tests,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.connectors = connectors;
    this.secrets = secrets;
    this.health = health;
    this.tests = tests;
    this.events = events;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public int receiveWebhook(
      TenantId tenantId, String sourceKey, String presentedToken, String contentType, byte[] body) {
    required(tenantId, "tenantId");
    required(sourceKey, "sourceKey");
    required(body, "body");
    IntakeRecords.Stored source =
        activeSource(tenantId, sourceKey)
            .orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
    if (presentedToken == null) {
      throw new VigilError(VigilError.Code.NOT_SIGNED_IN);
    }
    if (!secrets.verify(tenantId, source.webhookTokenRefId(), presentedToken)) {
      throw new VigilError(VigilError.Code.BAD_TOKEN);
    }
    MonitoringKind kind = source.view().sourceKind();
    ParseResult parsed =
        connectors.parse(ConnectorKind.fromKey(kind.key()).orElseThrow(), body, contentType);
    Integer entries =
        tx.execute(status -> store(tenantId, source.view(), contentType, body, parsed));
    return entries;
  }

  /** AR-R-16 step 4, in the webhook's transaction; returns {@code entry_count}. */
  private int store(
      TenantId tenantId, SourceView source, String contentType, byte[] body, ParseResult parsed) {
    long lastSequence = records.lockCounter(tenantId);
    Instant now = Timestamps.now(clock);
    records.lockSource(tenantId, source.id());
    String storedType = contentType == null ? "" : Texts.cut(contentType, CONTENT_TYPE_MAX);
    if (parsed instanceof ParseResult.Failed failed) {
      records.insertCall(tenantId, source.id(), now, storedType, body, 0, false);
      LOG.warn(
          "webhook body not read: tenant {} source {} ({})",
          tenantId.value(),
          source.sourceKey(),
          failed.reason());
      contact(tenantId, source, now);
      return 0;
    }
    List<ParsedEntry> entries = ((ParseResult.Parsed) parsed).entries();
    UUID callId =
        records.insertCall(tenantId, source.id(), now, storedType, body, entries.size(), true);
    List<AlertTypeRule.Mapping> mappings = records.mappings(tenantId, source.sourceKind());
    for (ParsedEntry entry : entries) {
      UUID rawId =
          records.insertRaw(
              tenantId, callId, source.id(), entry.entryIndex(), now, entry.payload());
      IntakeRecords.Normalised normalised = normalise(rawId, entry, source.sourceKind(), mappings);
      lastSequence = apply(tenantId, source, normalised, now, lastSequence);
    }
    records.updateCounter(tenantId, lastSequence);
    contact(tenantId, source, now);
    return entries.size();
  }

  private void contact(TenantId tenantId, SourceView source, Instant now) {
    records.updateContact(tenantId, source.id(), now);
    health.evaluate(tenantId, source.id(), now);
  }

  /** The normalised alert of an entry (DM-E-20; DM-C-11 as amended by BP-A-25). */
  private static IntakeRecords.Normalised normalise(
      UUID rawId, ParsedEntry entry, MonitoringKind kind, List<AlertTypeRule.Mapping> mappings) {
    if (entry instanceof ParsedEntry.Failed failed) {
      return failed(rawId, Texts.cut(failed.reason(), REASON_MAX));
    }
    ParsedEntry.Normalised normalised = (ParsedEntry.Normalised) entry;
    if (Texts.length(normalised.alertKey()) > ALERT_KEY_MAX) {
      return failed(rawId, KEY_TOO_LONG);
    }
    if (holdsNul(normalised)) {
      return failed(rawId, HOLDS_NUL);
    }
    List<IdentityPair> pairs =
        normalised.identityPairs().stream()
            .map(pair -> new IdentityPair(pair.name(), Texts.cut(pair.value(), PAIR_VALUE_MAX)))
            .toList();
    return new IntakeRecords.Normalised(
        rawId,
        true,
        null,
        normalised.alertKey(),
        normalised.status() == vigil.connectors.api.AlertStatus.FIRING
            ? AlertStatus.FIRING
            : AlertStatus.RESOLVED,
        AlertTypeRule.classify(kind, mappings, normalised.mappingFields()),
        pairs,
        Texts.cut(normalised.title(), TITLE_MAX),
        normalised.sourceTime(),
        Texts.cut(normalised.sourceSeverity(), SEVERITY_MAX));
  }

  /**
   * True when a text of the entry that would be stored holds U+0000, which PostgreSQL refuses
   * (DM-C-11 as amended by BP-A-26).
   */
  private static boolean holdsNul(ParsedEntry.Normalised entry) {
    List<String> texts = new ArrayList<>();
    texts.add(entry.alertKey());
    texts.add(entry.title());
    texts.add(entry.sourceSeverity());
    entry
        .identityPairs()
        .forEach(
            pair -> {
              texts.add(pair.name());
              texts.add(pair.value());
            });
    return texts.stream().anyMatch(text -> text != null && text.indexOf(0) >= 0);
  }

  private static IntakeRecords.Normalised failed(UUID rawId, String reason) {
    return new IntakeRecords.Normalised(
        rawId, false, reason, null, null, null, null, null, null, null);
  }

  /**
   * Applies a normalised entry to the alerts (DM-E21-R1, DM-SM-04); returns the counter's last
   * sequence after it.
   */
  private long apply(
      TenantId tenantId,
      SourceView source,
      IntakeRecords.Normalised entry,
      Instant now,
      long lastSequence) {
    if (!entry.normalised()) {
      records.insertNormalised(tenantId, entry, null, "not_applied", now);
      return lastSequence;
    }
    Optional<AlertView> firing = records.firingAlert(tenantId, source.id(), entry.alertKey());
    Instant stated = entry.sourceTime() == null ? now : entry.sourceTime();
    if (entry.reportedStatus() == AlertStatus.FIRING) {
      if (firing.isPresent()) {
        records.repeatFiring(tenantId, firing.get().id(), now);
        records.insertNormalised(tenantId, entry, firing.get().id(), "repeat_firing", now);
        return lastSequence;
      }
      long sequence = lastSequence + 1;
      UUID alertId =
          records.insertAlert(
              tenantId, source.id(), source.sourceKind(), entry, stated, now, sequence);
      records.insertStateChange(
          tenantId, alertId, null, AlertStatus.FIRING, now, entry.sourceTime(), entry.rawAlertId());
      records.insertNormalised(tenantId, entry, alertId, "created_alert", now);
      events.publishEvent(new AlertReceived(tenantId, alertId, sequence));
      events.publishEvent(new AlertStateChanged(tenantId, alertId, AlertStatus.FIRING));
      return sequence;
    }
    if (firing.isEmpty()) {
      records.insertNormalised(tenantId, entry, null, "orphan_resolved", now);
      return lastSequence;
    }
    UUID alertId = firing.get().id();
    records.resolve(tenantId, alertId, stated, now);
    records.insertStateChange(
        tenantId,
        alertId,
        AlertStatus.FIRING,
        AlertStatus.RESOLVED,
        now,
        entry.sourceTime(),
        entry.rawAlertId());
    records.insertNormalised(tenantId, entry, alertId, "resolved_alert", now);
    events.publishEvent(new AlertStateChanged(tenantId, alertId, AlertStatus.RESOLVED));
    return lastSequence;
  }

  @Override
  public List<AlertView> alertsAfter(TenantId tenantId, long sequence, int limit) {
    required(tenantId, "tenantId");
    if (sequence < 0 || limit < 1 || limit > PAGE_MAX) {
      throw new IllegalArgumentException("sequence 0 or more and limit 1 to 100");
    }
    return List.copyOf(records.alertsAfter(tenantId, sequence, limit));
  }

  @Override
  public Optional<AlertView> alert(TenantId tenantId, UUID alertId) {
    required(tenantId, "tenantId");
    required(alertId, "alertId");
    return records.alert(tenantId, alertId);
  }

  @Override
  public List<SourceView> sources(TenantId tenantId) {
    required(tenantId, "tenantId");
    return records.sources(tenantId, false).stream().map(IntakeRecords.Stored::view).toList();
  }

  @Override
  public Optional<SourceView> source(TenantId tenantId, UUID sourceId) {
    required(tenantId, "tenantId");
    required(sourceId, "sourceId");
    return records.source(tenantId, sourceId).map(IntakeRecords.Stored::view);
  }

  @Override
  public boolean healthyThroughout(TenantId tenantId, UUID sourceId, Instant from, Instant to) {
    required(tenantId, "tenantId");
    required(sourceId, "sourceId");
    required(from, "from");
    required(to, "to");
    if (from.isAfter(to) || to.isAfter(Timestamps.now(clock))) {
      throw new IllegalArgumentException("from at or before to, and to at or before now");
    }
    if (records.source(tenantId, sourceId).isEmpty()) {
      return false;
    }
    return records
            .healthAt(tenantId, sourceId, from)
            .filter(Health.HEALTHY.key()::equals)
            .isPresent()
        && !records.changedWithin(tenantId, sourceId, from, to);
  }

  @Override
  public SourceTestResult testSource(TenantId tenantId, String sourceKey) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, "tenantId");
    required(sourceKey, "sourceKey");
    IntakeRecords.Stored source =
        activeSource(tenantId, sourceKey)
            .orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
    return tests.test(tenantId, source.view());
  }

  @Override
  public List<AlertTypeView> alertTypes(TenantId tenantId) {
    required(tenantId, "tenantId");
    return List.copyOf(records.alertTypes(tenantId));
  }

  /** The active source with that key; a text that is not a valid key finds none. */
  private Optional<IntakeRecords.Stored> activeSource(TenantId tenantId, String sourceKey) {
    if (!KEY.matcher(sourceKey).matches()) {
      return Optional.empty();
    }
    return records.activeSource(tenantId, sourceKey);
  }

  private static void required(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + ": required");
    }
  }
}
