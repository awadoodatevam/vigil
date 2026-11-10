package vigil.knowledge;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.DataCategory;
import vigil.aigateway.api.EmbeddingRequest;
import vigil.aigateway.api.EmbeddingResult;
import vigil.knowledge.KnowledgeRecords.Embedding;
import vigil.knowledge.api.EmbeddingState;
import vigil.knowledge.api.KnowledgeImport;
import vigil.knowledge.api.KnowledgePart;
import vigil.knowledge.api.KnowledgePart.PastIncidentPart;
import vigil.knowledge.api.KnowledgePart.RunbookPart;
import vigil.knowledge.api.KnowledgePart.SettingsPart;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.knowledge.api.PastIncidentView;
import vigil.knowledge.api.RunbookView;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;
import vigil.shared.VigilError;

/**
 * Seed part 8 (DM-R-56 (8), DM-R-57, AR-B-08, AR-B-09; P-20 spec Section 6.6): the checks, the
 * batched embedding call before the transaction and the store as a whole.
 */
@Component
class KnowledgeImporter implements KnowledgeImport {

  static final String REFUSED = "Part knowledge refused.";
  static final String EXIST = "knowledge records already exist";
  static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  static final List<String> KINDS = List.of("server", "application", "database", "queue", "check");
  private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,18}$");
  private static final Pattern DECIMAL = Pattern.compile("^[0-9]+(\\.[0-9]+)?$");
  private static final String NUL = "holds the character U+0000";

  private final KnowledgeRecords records;
  private final AiGatewayService gateway;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** A past incident row after its checks, with its lists. */
  private record Past(Map<String, String> fields, List<String> types, List<String> services) {}

  KnowledgeImporter(
      KnowledgeRecords records,
      AiGatewayService gateway,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.gateway = gateway;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Map<String, UUID> importPart(
      TenantId tenantId, KnowledgePart part, Instant importStartedAt, Map<String, UUID> handles) {
    TransactionGuard.assertNoTransaction();
    if (tenantId == null
        || part == null
        || importStartedAt == null
        || handles == null
        || part.runbooks() == null
        || part.pastIncidents() == null
        || part.settings() == null
        || part.runbooks().stream()
            .anyMatch(
                r ->
                    r == null
                        || r.applicableAlertTypes() == null
                        || r.applicableComponentKinds() == null
                        || r.applicableAlertTypes().contains(null)
                        || r.applicableComponentKinds().contains(null))
        || part.pastIncidents().stream().anyMatch(p -> p == null || p.fields() == null)) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    if (records.anyRecords(tenantId)) {
      throw refused(List.of(EXIST));
    }
    Set<String> problems = new LinkedHashSet<>();
    checkRunbooks(part.runbooks(), problems);
    List<Past> past = checkPastIncidents(part.pastIncidents(), problems);
    checkSettings(part.settings(), problems);
    if (!problems.isEmpty()) {
      throw refused(List.copyOf(problems));
    }
    SettingsPart settings = part.settings();
    String providerKey = settings.embeddingProviderKey();
    List<String> texts =
        past.stream()
            .map(
                p ->
                    SearchTexts.of(
                        p.types(), p.services(), p.fields().get("hypothesis_component_key")))
            .toList();
    EmbeddingResult.Embedded embedded = null;
    if (!past.isEmpty()) {
      EmbeddingResult answer =
          gateway.embed(
              new EmbeddingRequest(
                  tenantId,
                  providerKey,
                  Ids.newId(),
                  null,
                  Set.of(DataCategory.INCIDENT_HISTORY),
                  texts));
      String failure =
          switch (answer) {
            case EmbeddingResult.Unavailable unavailable -> unavailable.outcome().key();
            case EmbeddingResult.Embedded e ->
                e.vectors().size() == texts.size() && Vectors.usable(e.vectors())
                    ? null
                    : "unusable vectors";
          };
      if (failure != null) {
        throw refused(
            List.of(
                "knowledge_settings.embedding_provider_key: the past incidents could not be"
                    + " embedded with "
                    + providerKey
                    + " ("
                    + failure
                    + ")"));
      }
      embedded = (EmbeddingResult.Embedded) answer;
    }
    EmbeddingResult.Embedded vectors = embedded;
    boolean stored =
        Boolean.TRUE.equals(
            tx.execute(
                status -> {
                  if (records.anyRecords(tenantId)) {
                    return false;
                  }
                  Instant now = Timestamps.now(clock);
                  for (RunbookPart runbook : part.runbooks()) {
                    records.insertRunbook(tenantId, runbook(runbook), now);
                  }
                  for (int i = 0; i < past.size(); i++) {
                    PastIncidentView view = pastIncident(past.get(i), importStartedAt);
                    records.insertPastIncident(tenantId, view, now);
                    records.insertEmbedding(
                        tenantId,
                        new Embedding(
                            "past_incident",
                            view.pastIncidentKey(),
                            texts.get(i),
                            vectors.vectors().get(i)),
                        providerKey,
                        vectors.modelName(),
                        now,
                        false);
                  }
                  records.insertSettings(
                      tenantId,
                      new KnowledgeSettingsView(
                          new BigDecimal(settings.minSimilarity()),
                          Long.parseLong(settings.runbookLimit()),
                          providerKey,
                          EmbeddingState.READY,
                          null),
                      now);
                  return true;
                }));
    if (!stored) {
      throw refused(List.of(EXIST));
    }
    return Map.of();
  }

  private static RunbookView runbook(RunbookPart part) {
    return new RunbookView(
        null,
        part.runbookKey(),
        Long.parseLong(part.version()),
        part.title(),
        sorted(part.applicableAlertTypes()),
        sorted(part.applicableComponentKinds()),
        part.text(),
        true);
  }

  private static PastIncidentView pastIncident(Past past, Instant importStartedAt) {
    Map<String, String> f = past.fields();
    Instant opened =
        importStartedAt.minus(
            Duration.ofMinutes(Long.parseLong(f.get("opened_minutes_before_import"))));
    String action = f.get("fix_action_key");
    return new PastIncidentView(
        null,
        f.get("past_incident_key"),
        f.get("title"),
        f.get("record_label"),
        opened,
        after(opened, f.get("acknowledged_after_minutes")),
        after(opened, f.get("recovered_after_minutes")),
        Long.parseLong(f.get("alert_count")),
        sorted(past.types()),
        sorted(past.services()),
        f.get("hypothesis_component_key"),
        f.get("summary"),
        f.get("fix_description"),
        action.isEmpty() ? null : action,
        Long.parseLong(f.get("fix_success_count")),
        true);
  }

  private static Instant after(Instant opened, String minutes) {
    return minutes.isEmpty() ? null : opened.plus(Duration.ofMinutes(Long.parseLong(minutes)));
  }

  private static List<String> sorted(List<String> values) {
    return values.stream().sorted(SearchTexts.CODE_POINTS).toList();
  }

  // Check 2: runbooks.

  private static void checkRunbooks(List<RunbookPart> runbooks, Set<String> problems) {
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < runbooks.size(); i++) {
      RunbookPart r = runbooks.get(i);
      String at = "runbooks[" + i + "].";
      if (nul(problems, at, "runbook_key", r.runbookKey())) {
        if (!isKey(r.runbookKey())) {
          problems.add(at + "runbook_key: not a key");
        } else if (!keys.add(r.runbookKey())) {
          problems.add(at + "runbook_key: used more than once");
        }
      }
      if (nul(problems, at, "version", r.version()) && !wholeAtLeast(r.version(), 1)) {
        problems.add(at + "version: a whole number of at least 1");
      }
      if (nul(problems, at, "title", r.title()) && !length(r.title(), 200)) {
        problems.add(at + "title: 1 to 200 characters");
      }
      if (nul(problems, at, "applicable_alert_types", r.applicableAlertTypes())) {
        if (r.applicableAlertTypes().isEmpty()) {
          problems.add(at + "applicable_alert_types: at least one alert type");
        } else {
          keyList(problems, at + "applicable_alert_types", r.applicableAlertTypes());
        }
      }
      if (nul(problems, at, "applicable_component_kinds", r.applicableComponentKinds())) {
        Set<String> seen = new HashSet<>();
        for (String kind : r.applicableComponentKinds()) {
          if (!KINDS.contains(kind)) {
            problems.add(
                at
                    + "applicable_component_kinds: '"
                    + kind
                    + "' is not one of server, application, database, queue, check");
          }
          if (!seen.add(kind)) {
            problems.add(at + "applicable_component_kinds: '" + kind + "' appears more than once");
          }
        }
      }
      if (nul(problems, at, "text", r.text()) && !length(r.text(), 20_000)) {
        problems.add(at + "text: 1 to 20000 characters");
      }
    }
  }

  // Check 3: past incidents.

  private static List<Past> checkPastIncidents(List<PastIncidentPart> rows, Set<String> problems) {
    Set<String> keys = new HashSet<>();
    List<Past> past = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      Map<String, String> f = rows.get(i).fields();
      String at = "past_incidents[" + i + "].";
      String key = f.get("past_incident_key");
      if (nul(problems, at, "past_incident_key", key)) {
        if (!isKey(key)) {
          problems.add(at + "past_incident_key: not a key");
        } else if (!keys.add(key)) {
          problems.add(at + "past_incident_key: used more than once");
        }
      }
      text(problems, at, "title", f.get("title"), 200);
      text(problems, at, "record_label", f.get("record_label"), 60);
      String opened = f.get("opened_minutes_before_import");
      if (nul(problems, at, "opened_minutes_before_import", opened)
          && !(wholeAtLeast(opened, 1) && Long.parseLong(opened) <= 43_200)) {
        problems.add(at + "opened_minutes_before_import: a whole number from 1 to 43200");
      }
      for (String column : List.of("acknowledged_after_minutes", "recovered_after_minutes")) {
        String value = f.get(column);
        if (nul(problems, at, column, value)
            && (value == null || !(value.isEmpty() || wholeAtLeast(value, 0)))) {
          problems.add(at + column + ": empty or a whole number of at least 0");
        }
      }
      String count = f.get("alert_count");
      if (nul(problems, at, "alert_count", count) && !wholeAtLeast(count, 1)) {
        problems.add(at + "alert_count: a whole number of at least 1");
      }
      List<String> types = split(f.get("alert_types"));
      if (nul(problems, at, "alert_types", f.get("alert_types"))) {
        keyList(problems, at + "alert_types", types);
      }
      List<String> services = split(f.get("affected_service_keys"));
      if (nul(problems, at, "affected_service_keys", f.get("affected_service_keys"))) {
        keyList(problems, at + "affected_service_keys", services);
      }
      String component = f.get("hypothesis_component_key");
      if (nul(problems, at, "hypothesis_component_key", component) && !isKey(component)) {
        problems.add(at + "hypothesis_component_key: not a key");
      }
      text(problems, at, "summary", f.get("summary"), 2000);
      text(problems, at, "fix_description", f.get("fix_description"), 2000);
      String action = f.get("fix_action_key");
      if (nul(problems, at, "fix_action_key", action)
          && (action == null || !(action.isEmpty() || isKey(action)))) {
        problems.add(at + "fix_action_key: empty or a key");
      }
      String successes = f.get("fix_success_count");
      if (nul(problems, at, "fix_success_count", successes) && !wholeAtLeast(successes, 0)) {
        problems.add(at + "fix_success_count: a whole number of at least 0");
      }
      past.add(new Past(f, types, services));
    }
    return past;
  }

  // Check 4: settings.

  private static void checkSettings(SettingsPart s, Set<String> problems) {
    String at = "knowledge_settings.";
    if (nul(problems, at, "min_similarity", s.minSimilarity()) && !similarity(s.minSimilarity())) {
      problems.add(at + "min_similarity: a decimal from 0 to 1 with at most 2 decimal places");
    }
    if (nul(problems, at, "runbook_limit", s.runbookLimit())
        && !wholeAtLeast(s.runbookLimit(), 1)) {
      problems.add(at + "runbook_limit: a whole number of at least 1");
    }
    if (nul(problems, at, "embedding_provider_key", s.embeddingProviderKey())
        && !isKey(s.embeddingProviderKey())) {
      problems.add(at + "embedding_provider_key: not a key");
    }
    if (nul(problems, at, "embedding_state", s.embeddingState())
        && !"ready".equals(s.embeddingState())) {
      problems.add(at + "embedding_state: must be ready");
    }
  }

  private static boolean similarity(String text) {
    if (text == null || !DECIMAL.matcher(text).matches()) {
      return false;
    }
    BigDecimal value = new BigDecimal(text);
    return value.compareTo(BigDecimal.ONE) <= 0 && value.stripTrailingZeros().scale() <= 2;
  }

  /**
   * False with the U+0000 problem when a text value (or an element of a list) holds U+0000; true
   * when its other checks apply.
   */
  private static boolean nul(Set<String> problems, String at, String field, Object value) {
    boolean holds =
        switch (value) {
          case String text -> text.indexOf('\u0000') >= 0;
          case List<?> list ->
              list.stream().anyMatch(e -> e instanceof String t && t.indexOf('\u0000') >= 0);
          case null, default -> false;
        };
    if (holds) {
      problems.add(at + field + ": " + NUL);
    }
    return !holds;
  }

  private static void text(Set<String> problems, String at, String field, String value, int max) {
    if (nul(problems, at, field, value) && !length(value, max)) {
      problems.add(at + field + ": 1 to " + max + " characters");
    }
  }

  private static void keyList(Set<String> problems, String at, List<String> values) {
    Set<String> seen = new HashSet<>();
    for (String value : values) {
      if (!isKey(value)) {
        problems.add(at + ": '" + value + "' is not a key");
      }
      if (!seen.add(value)) {
        problems.add(at + ": '" + value + "' appears more than once");
      }
    }
  }

  /** A value of DD-R-07 (5): split on {@code ;}, the empty value the empty list. */
  static List<String> split(String value) {
    if (value == null) {
      return List.of("");
    }
    if (value.isEmpty()) {
      return List.of();
    }
    return List.of(value.split(";", -1));
  }

  private static boolean isKey(String value) {
    return value != null && KEY.matcher(value).matches();
  }

  private static boolean wholeAtLeast(String value, long min) {
    return value != null && WHOLE.matcher(value).matches() && Long.parseLong(value) >= min;
  }

  private static boolean length(String value, int max) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    return value.codePointCount(0, value.length()) <= max;
  }

  private static VigilError refused(List<String> problems) {
    return new VigilError(VigilError.Code.INVALID, REFUSED, Map.of("problems", problems));
  }
}
