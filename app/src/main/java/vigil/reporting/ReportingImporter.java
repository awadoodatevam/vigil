package vigil.reporting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.reporting.api.MetricKey;
import vigil.reporting.api.MetricUnit;
import vigil.reporting.api.ReportingImport;
import vigil.reporting.api.ReportingPart;
import vigil.reporting.api.ReportingPart.BaselinePart;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 17: the baseline values (DM-E-80, DD-F-07), checked as a whole (DM-R-57;
 * P-23 spec Section 6.11). It writes no audit event.
 */
@Service
class ReportingImporter implements ReportingImport {

  static final String LABEL = "Illustrative baseline";
  private static final Pattern VALUE = Pattern.compile("^[0-9]{1,11}(\\.[0-9])?$");
  private static final String SECTION = "baseline_values";

  private final BaselineRecords records;
  private final TransactionTemplate tx;
  private final Clock clock;

  ReportingImporter(
      BaselineRecords records, PlatformTransactionManager transactionManager, Clock clock) {
    this.records = records;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Map<String, UUID> importPart(
      TenantId tenantId, ReportingPart part, Instant importStartedAt, Map<String, UUID> handles) {
    require(tenantId, part, importStartedAt, handles);
    require(part.baselines());
    require(part.baselines().toArray());
    return tx.execute(
        status -> {
          List<String> problems = problems(tenantId, part);
          if (!problems.isEmpty()) {
            throw new VigilError(
                VigilError.Code.INVALID, "Part reporting refused.", Map.of("problems", problems));
          }
          Instant now = Timestamps.now(clock);
          for (BaselinePart baseline : part.baselines()) {
            records.insert(
                tenantId,
                MetricKey.fromKey(baseline.metricKey()).orElseThrow(),
                new BigDecimal(baseline.value()),
                baseline.label(),
                now);
          }
          return Map.of();
        });
  }

  private List<String> problems(TenantId tenantId, ReportingPart part) {
    if (records.hasBaselines(tenantId)) {
      return List.of("baseline values already exist");
    }
    List<String> problems = new ArrayList<>();
    Set<MetricKey> keys = EnumSet.noneOf(MetricKey.class);
    for (int i = 0; i < part.baselines().size(); i++) {
      String at = SECTION + "[" + i + "]";
      BaselinePart baseline = part.baselines().get(i);
      MetricKey key = MetricKey.fromKey(baseline.metricKey()).orElse(null);
      if (key == null) {
        problems.add(
            at
                + ".metric_key: one of time_to_acknowledge, time_to_recover, alerts_suppressed,"
                + " incidents_merged");
      } else if (!keys.add(key)) {
        problems.add(at + ".metric_key: used more than once");
      }
      String value = baseline.value();
      if (value == null || !VALUE.matcher(value).matches()) {
        problems.add(at + ".value: a number from 0 with at most one decimal place");
      } else if (key != null
          && key.unit() == MetricUnit.COUNT
          && new BigDecimal(value).stripTrailingZeros().scale() > 0) {
        problems.add(at + ".value: a whole number for a count");
      }
      if (key != null && !key.unit().key().equals(baseline.unit())) {
        problems.add(at + ".unit: " + key.unit().key() + " for " + key.key());
      }
      if (!LABEL.equals(baseline.label())) {
        problems.add(at + ".label: " + LABEL);
      }
    }
    Set<MetricKey> missing = EnumSet.allOf(MetricKey.class);
    missing.removeAll(keys);
    if (!missing.isEmpty()) {
      problems.add(
          SECTION
              + ": one value per metric, missing "
              + missing.stream().map(MetricKey::key).collect(Collectors.joining(", ")));
    }
    return problems;
  }

  private static void require(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
