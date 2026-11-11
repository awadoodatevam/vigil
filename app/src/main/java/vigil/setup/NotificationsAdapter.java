package vigil.setup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.notifications.api.NotificationsImport;
import vigil.notifications.api.NotificationsPart;
import vigil.notifications.api.NotificationsPart.AddressPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/**
 * Seed part 15, notifications, imported through {@link NotificationsImport} (DM-R-56 (15)): the
 * section {@code delivery_addresses} of {@code 07-settings.yaml}.
 */
@Component
class NotificationsAdapter implements SeedPartImporter {

  private static final String SECTION = "delivery_addresses";

  private final NotificationsImport notificationsImport;

  NotificationsAdapter(NotificationsImport notificationsImport) {
    this.notificationsImport = notificationsImport;
  }

  @Override
  public int part() {
    return 15;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    List<Row> rows =
        shapes.list(
            files.get(SeedParts.SETTINGS),
            SECTION,
            List.of("subject_kind", "subject_key", "channel", "address", "credential_ref"),
            Set.of());
    shapes.refuseIfAny(SeedParts.part(part()).name());
    notificationsImport.importPart(
        tenantId,
        new NotificationsPart(
            rows.stream()
                .map(
                    row ->
                        new AddressPart(
                            row.get("subject_kind"),
                            row.get("subject_key"),
                            row.get("channel"),
                            row.get("address"),
                            row.get("credential_ref")))
                .toList()),
        importStartedAt,
        handles);
    return new PartResult(Map.of(SECTION, rows.size()), Map.of());
  }
}
