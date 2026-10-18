package vigil.connectors.api;

import java.util.Arrays;
import java.util.Optional;

/** The purposes of a credential reference (DM-E-11 {@code purpose}, complete list, in order). */
public enum CredentialPurpose {
  WEBHOOK_TOKEN("webhook_token"),
  EXTERNAL_API("external_api"),
  TARGET_DIAGNOSTIC("target_diagnostic"),
  TARGET_ACTION("target_action"),
  SMTP("smtp"),
  WEBHOOK_URL("webhook_url");

  private final String key;

  CredentialPurpose(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The purpose with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<CredentialPurpose> fromKey(String key) {
    return Arrays.stream(values()).filter(purpose -> purpose.key.equals(key)).findFirst();
  }
}
