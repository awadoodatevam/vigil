package vigil.routing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.identity.api.IdentityService;
import vigil.incidents.api.Severity;
import vigil.routing.api.ConditionPart;
import vigil.routing.api.EscalationLevel;
import vigil.routing.api.LevelPart;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingImport;
import vigil.routing.api.RoutingOperator;
import vigil.routing.api.RoutingPart;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.RulePart;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 11: routing rule set revision 1 and escalation policy revision 1
 * (AR-B-11, DD-F-05), checked as P-15 spec Section 6.5 (DM-R-57). It writes no audit event and
 * publishes no event.
 */
@Service
class RoutingImporter implements RoutingImport {

  private static final Logger LOG = LoggerFactory.getLogger(RoutingImporter.class);

  private static final String RULES = "routing_rule_set_revision_1";
  private static final String LEVELS = "escalation_policy_revision_1.levels";
  private static final Actor SETUP = Actor.module("vigil-setup");
  private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,9}$");

  private final RoutingRecords records;
  private final IdentityService identity;
  private final Clock clock;

  RoutingImporter(RoutingRecords records, IdentityService identity, Clock clock) {
    this.records = records;
    this.identity = identity;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId, RoutingPart part, Instant importStartedAt, Map<String, UUID> handles) {
    if (tenantId == null
        || part == null
        || part.rules() == null
        || part.levels() == null
        || part.rules().stream().anyMatch(Objects::isNull)
        || part.levels().stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("tenantId, part, rules and levels: required");
    }
    records.lock(tenantId);
    List<String> problems = new ArrayList<>();
    List<RoutingRule> rules = new ArrayList<>();
    if (records.anyRevision(tenantId)) {
      problems.add("routing rules already exist");
    } else {
      Set<String> keys = new HashSet<>();
      for (int i = 0; i < part.rules().size(); i++) {
        checkRule(part.rules().get(i), RULES + ".rules[" + i + "]", keys, rules, problems);
      }
      if (problems.isEmpty()) {
        RuleSetValidation.problems(rules, Routing.activeTeams(identity, tenantId))
            .forEach(problem -> problems.add(RULES + ": " + problem.text()));
      }
      checkLevels(part.levels(), problems);
    }
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID, "Part routing refused.", Map.of("problems", problems));
    }
    List<RoutingRule> stored = rules.stream().map(RoutingJson::stored).toList();
    List<EscalationLevel> levels = new ArrayList<>();
    for (LevelPart level : part.levels()) {
      levels.add(new EscalationLevel(levels.size() + 1, RoutingJson.sorted(level.teamKeys())));
    }
    Instant now = Timestamps.now(clock);
    records.insertRuleSet(
        tenantId,
        Ids.newId(),
        1,
        RoutingJson.write(RoutingJson.rules(stored)),
        SETUP,
        now,
        ChangeSummary.of(null, stored));
    records.insertPolicy(tenantId, 1, levels, SETUP, now);
    LOG.info("routing rules of tenant {} saved as revision 1", tenantId.value());
    return Map.of();
  }

  /** Step (2) for one rule; adds the rule to {@code rules} as a {@code RoutingRule} when it can. */
  private static void checkRule(
      RulePart rule, String at, Set<String> keys, List<RoutingRule> rules, List<String> problems) {
    int before = problems.size();
    if (!RuleSetValidation.isKey(rule.ruleKey())) {
      problems.add(at + ".rule_key: not a key");
    } else if (!keys.add(rule.ruleKey())) {
      problems.add(at + ".rule_key: used more than once");
    }
    if (rule.name() != null
        && rule.name().codePointCount(0, rule.name().length()) > Routing.NAME_MAX) {
      problems.add(at + ".name: at most 100 characters");
    }
    List<RoutingCondition> conditions = new ArrayList<>();
    if (rule.conditions() == null) {
      problems.add(at + ".conditions: missing");
    } else {
      for (int j = 0; j < rule.conditions().size(); j++) {
        String condition = at + ".conditions[" + j + "]";
        conditions.add(checkCondition(rule.conditions().get(j), condition, problems));
      }
    }
    Optional<Severity> severity = Severity.fromKey(rule.severity());
    if (severity.isEmpty()) {
      problems.add(at + ".severity: P1, P2, P3 or P4");
    }
    Optional<OwnerMode> ownerMode = OwnerMode.fromKey(rule.ownerMode());
    if (ownerMode.isEmpty()) {
      problems.add(at + ".owner_mode: fixed_team or hypothesis_component_owner");
    }
    List<String> informed = rule.informedTeamKeys();
    if (informed == null) {
      problems.add(at + ".informed_team_keys: missing");
    } else {
      for (int j = 0; j < informed.size(); j++) {
        if (informed.get(j) == null || informed.get(j).isEmpty()) {
          problems.add(at + ".informed_team_keys[" + j + "]: not a key");
        }
      }
    }
    String inform = rule.informAffectedServiceOwners();
    if (!"true".equals(inform) && !"false".equals(inform)) {
      problems.add(at + ".inform_affected_service_owners: true or false");
    }
    if (problems.size() == before) {
      rules.add(
          new RoutingRule(
              rule.ruleKey(),
              rule.name() == null ? "" : rule.name(),
              List.copyOf(conditions),
              severity.get(),
              ownerMode.get(),
              rule.ownerTeamKey() == null ? "" : rule.ownerTeamKey(),
              List.copyOf(informed),
              Boolean.parseBoolean(inform)));
    }
  }

  /** Step (2) for one condition; {@code null} when its field or operator is not one. */
  private static RoutingCondition checkCondition(
      ConditionPart condition, String at, List<String> problems) {
    if (condition == null) {
      throw new IllegalArgumentException("conditions: no null condition");
    }
    Optional<RoutingField> field = RoutingField.fromKey(condition.field());
    if (field.isEmpty()) {
      problems.add(at + ".field: not a routing field");
      return null;
    }
    Optional<RoutingOperator> operator =
        RoutingOperator.fromKey(condition.operator()).filter(field.get().operators()::contains);
    if (operator.isEmpty()) {
      problems.add(at + ".operator: not an operator of " + field.get().key());
      return null;
    }
    List<String> values = condition.values();
    if (RoutingJson.isList(operator.get())) {
      if (values == null || values.isEmpty()) {
        problems.add(at + ".value: at least one value");
      }
    } else if (values == null || values.size() != 1) {
      problems.add(at + ".value: one value");
    }
    return new RoutingCondition(field.get(), operator.get(), values);
  }

  /** Step (4): levels 1, 2, ... without gaps, each with at least one team (DM-E-42). */
  private static void checkLevels(List<LevelPart> levels, List<String> problems) {
    if (levels.isEmpty()) {
      problems.add(LEVELS + ": at least one level");
    }
    for (int i = 0; i < levels.size(); i++) {
      LevelPart level = levels.get(i);
      String at = LEVELS + "[" + i + "]";
      if (level.level() == null
          || !WHOLE.matcher(level.level()).matches()
          || Integer.parseInt(level.level()) != i + 1) {
        problems.add(at + ".level: must be " + (i + 1));
      }
      if (level.teamKeys() == null || level.teamKeys().isEmpty()) {
        problems.add(at + ".team_keys: at least one team");
      } else {
        for (int j = 0; j < level.teamKeys().size(); j++) {
          if (!RuleSetValidation.isKey(level.teamKeys().get(j))) {
            problems.add(at + ".team_keys[" + j + "]: not a key");
          }
        }
      }
    }
  }
}
