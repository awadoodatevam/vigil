package vigil.notifications.api;

import java.util.UUID;

/** A delivery address (DM-E-77); {@code credentialRefId} is {@code null} for email. */
public record DeliveryAddressView(
    UUID id,
    RecipientKind subjectKind,
    String subjectKey,
    Channel channel,
    String address,
    UUID credentialRefId,
    boolean active) {}
