package vigil.notifications.api;

import java.time.Instant;
import java.util.UUID;
import vigil.incidents.api.NotifiedPurpose;

/**
 * A notification record (DM-E-78): {@code origin_ref} as its module, entity and id; {@code
 * viaTeamKey} {@code null} for a team recipient, {@code error} {@code null} when sent.
 */
public record NotificationRecordView(
    UUID id,
    UUID incidentId,
    NotifiedPurpose purpose,
    String originModule,
    String originEntity,
    UUID originId,
    RecipientKind recipientKind,
    String recipientKey,
    String viaTeamKey,
    Channel channel,
    String address,
    String content,
    String link,
    DeliveryStatus status,
    String error,
    Instant at) {}
