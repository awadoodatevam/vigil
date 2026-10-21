package vigil.intake.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** The requests of other modules to vigil-intake (PM-M06). */
public interface IntakeService {

  /**
   * A webhook call (AR-I-90, AR-R-16): checks the source and its token, stores the call and its
   * entries and applies them to the alerts; returns the number of entries. {@code presentedToken}
   * and {@code contentType} may be {@code null}.
   */
  int receiveWebhook(
      TenantId tenantId, String sourceKey, String presentedToken, String contentType, byte[] body);

  /**
   * The alerts with a {@code sequence} greater than {@code sequence}, ascending, at most {@code
   * limit} (1 to 100) (PM-I-07).
   */
  List<AlertView> alertsAfter(TenantId tenantId, long sequence, int limit);

  /** The alert with that id, or empty (PM-I-29). */
  Optional<AlertView> alert(TenantId tenantId, UUID alertId);

  /** Every source, active or not, in code point order of {@code source_key} (PM-I-40). */
  List<SourceView> sources(TenantId tenantId);

  /** The source with that id, or empty. */
  Optional<SourceView> source(TenantId tenantId, UUID sourceId);

  /**
   * Whether the source's health was {@code healthy} during the whole interval (DM-E23-R1); {@code
   * false} for an unknown source.
   */
  boolean healthyThroughout(TenantId tenantId, UUID sourceId, Instant from, Instant to);

  /**
   * The connection test of the active source with that key, recorded as a periodic test
   * (PM-M06-07).
   *
   * <p>external call: not inside a transaction
   */
  SourceTestResult testSource(TenantId tenantId, String sourceKey);

  /** Every alert type definition, {@code unclassified} included, in code point order. */
  List<AlertTypeView> alertTypes(TenantId tenantId);
}
