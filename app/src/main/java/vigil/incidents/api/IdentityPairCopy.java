package vigil.incidents.api;

/** The copy of an alert's first identity pair in an alert copy (DM-E-34, BP-A-27). */
public record IdentityPairCopy(String name, String value) {

  public IdentityPairCopy {
    if (name == null || value == null) {
      throw new IllegalArgumentException("identity pair: name and value required");
    }
  }
}
