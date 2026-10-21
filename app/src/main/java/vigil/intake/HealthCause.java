package vigil.intake;

/** The cause of a source health change (DM-E-23 {@code cause}, DM-SM-03). */
enum HealthCause {
  SOURCE_CREATED("source_created"),
  CONTACT("contact"),
  CONTACT_AGE("contact_age"),
  TEST_FAILED("test_failed");

  private final String key;

  HealthCause(String key) {
    this.key = key;
  }

  String key() {
    return key;
  }
}
