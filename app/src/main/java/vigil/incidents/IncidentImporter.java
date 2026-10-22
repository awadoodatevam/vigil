package vigil.incidents;

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
import vigil.incidents.api.IncidentImport;
import vigil.incidents.api.IncidentSettingsPart;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 9: the incident settings (AR-B-11, DD-F-07), checked by the rules of
 * DM-E-40 (DM-R-57). It writes no audit event and publishes no event.
 */
@Service
class IncidentImporter implements IncidentImport {

  private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,9}$");
  private static final int INTERVAL_MAX = 60;
  private static final String SECTION = "incident_settings: ";

  private final IncidentRecords records;
  private final Clock clock;

  IncidentImporter(IncidentRecords records, Clock clock) {
    this.records = records;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId,
      IncidentSettingsPart part,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    if (tenantId == null || part == null) {
      throw new IllegalArgumentException("tenantId and part: required");
    }
    List<String> problems = new ArrayList<>();
    if (records.settings(tenantId).isPresent()) {
      problems.add("incident settings already exist");
    }
    boolean window = whole(part.reopenWindowS());
    if (!window) {
      problems.add(SECTION + "reopen_window_s: not a whole number of seconds");
    }
    boolean interval = whole(part.closureCheckIntervalS());
    if (!interval) {
      problems.add(SECTION + "closure_check_interval_s: not a whole number of seconds");
    } else {
      int seconds = Integer.parseInt(part.closureCheckIntervalS());
      if (seconds < 1 || seconds > INTERVAL_MAX) {
        problems.add(SECTION + "closure_check_interval_s: 1 to 60");
      }
    }
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID, "Part incidents refused.", Map.of("problems", problems));
    }
    records.insertSettings(
        tenantId,
        Integer.parseInt(part.reopenWindowS()),
        Integer.parseInt(part.closureCheckIntervalS()),
        Timestamps.now(clock));
    return Map.of();
  }

  private static boolean whole(String value) {
    return value != null && WHOLE.matcher(value).matches();
  }
}
