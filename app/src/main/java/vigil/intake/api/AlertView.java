package vigil.intake.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An alert (DM-E-21): one firing episode of one alert key at one source. {@code resolvedAt} and
 * {@code resolveReceivedAt} are {@code null} while it is firing.
 */
public record AlertView(
    UUID id,
    long sequence,
    UUID sourceId,
    MonitoringKind sourceKind,
    String alertKey,
    String alertType,
    List<IdentityPair> identityPairs,
    String title,
    AlertStatus status,
    Instant firedAt,
    Instant firstReceivedAt,
    Instant resolvedAt,
    Instant resolveReceivedAt,
    long entryCount) {

  public AlertView {
    identityPairs = List.copyOf(identityPairs);
  }
}
