package vigil.api;

import java.util.UUID;
import vigil.identity.api.UserView;
import vigil.shared.TenantId;

/** The logged-in caller of a request: the host's tenant, the session and its user. */
record ApiCaller(TenantId tenantId, UUID sessionId, UserView user) {}
