package vigil.correlation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.correlation.api.CorrelationImport;
import vigil.correlation.api.CorrelationSettingsPart;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 10: the correlation settings (AR-B-11, DD-F-07), checked by the rules of
 * P-16 spec Section 6.5 (DM-R-57). It writes no audit event, publishes no event and starts no
 * worker.
 */
@Service
class CorrelationImporter implements CorrelationImport {

  private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,9}$");
  private static final String SECTION = "correlation_settings: ";
  private static final int INTERVAL_MAX = 5;

  private final CorrelationRecords records;
  private final Clock clock;

  CorrelationImporter(CorrelationRecords records, Clock clock) {
    this.records = records;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId,
      CorrelationSettingsPart part,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    Correlation.required(tenantId, part);
    if (records.settings(tenantId).isPresent()) {
      throw refused(List.of("correlation settings already exist"));
    }
    List<String> problems = new ArrayList<>();
    Integer window = whole(part.groupingWindowS(), "grouping_window_s", problems);
    Integer settle = whole(part.settlePeriodS(), "settle_period_s", problems);
    Integer maxForming = whole(part.maxFormingS(), "max_forming_s", problems);
    Integer interval =
        whole(part.stabilityCheckIntervalS(), "stability_check_interval_s", problems);
    if (window != null && window == 0) {
      problems.add(SECTION + "grouping_window_s: at least 1");
    }
    if (settle != null && settle == 0) {
      problems.add(SECTION + "settle_period_s: at least 1");
    }
    if (maxForming != null && settle != null && maxForming <= settle) {
      problems.add(SECTION + "max_forming_s: must be greater than settle_period_s");
    }
    if (interval != null && (interval < 1 || interval > INTERVAL_MAX)) {
      problems.add(SECTION + "stability_check_interval_s: 1 to 5");
    }
    if (!problems.isEmpty()) {
      throw refused(problems);
    }
    records.insertSettings(tenantId, window, settle, maxForming, interval, Timestamps.now(clock));
    return Map.of();
  }

  /** The value as a whole number of seconds, or {@code null} with its problem added. */
  private static Integer whole(String value, String field, List<String> problems) {
    if (value == null || !WHOLE.matcher(value).matches()) {
      problems.add(SECTION + field + ": not a whole number of seconds");
      return null;
    }
    return Integer.parseInt(value);
  }

  private static VigilError refused(List<String> problems) {
    return new VigilError(
        VigilError.Code.INVALID, "Part correlation refused.", Map.of("problems", problems));
  }
}
