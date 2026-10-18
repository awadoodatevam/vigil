package vigil.connectors.api;

import java.util.UUID;

/** A credential reference (DM-E-11). The locator does not leave vigil-connectors (SL-B-14). */
public record CredentialReferenceView(UUID id, CredentialPurpose purpose) {}
