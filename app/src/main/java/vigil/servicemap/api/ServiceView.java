package vigil.servicemap.api;

import java.util.List;

/**
 * A business service (DM-E-25); {@code componentKeys} sorted, {@code criticality} and {@code
 * ownerTeamKey} {@code null} when empty.
 */
public record ServiceView(
    String serviceKey,
    String name,
    Criticality criticality,
    String ownerTeamKey,
    List<String> componentKeys) {

  public ServiceView {
    componentKeys = List.copyOf(componentKeys);
  }
}
