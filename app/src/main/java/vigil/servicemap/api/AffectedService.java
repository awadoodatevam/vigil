package vigil.servicemap.api;

import java.util.List;

/** A service affected by a set of components, with those of them it has (DM-R-19, DM-V-10). */
public record AffectedService(String serviceKey, String name, List<String> viaComponentKeys) {

  public AffectedService {
    viaComponentKeys = List.copyOf(viaComponentKeys);
  }
}
