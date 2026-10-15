package vigil.shared;

import java.util.Objects;
import java.util.UUID;

/** The identifier of a tenant. */
public record TenantId(UUID value) {

  public TenantId {
    Objects.requireNonNull(value, "tenant id");
  }
}
