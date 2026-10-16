package vigil.identity.api;

import java.util.Locale;

/** The permissions of DM-E06-R1 (complete list, in its order). */
public enum Permission {
  VIEW,
  ACKNOWLEDGE,
  APPROVE_ACTION,
  MANAGE_SETUP,
  MANAGE_ROUTING,
  MANAGE_AI_SETTINGS,
  RERUN_AI_INVESTIGATION,
  EDIT_WORKFLOW,
  RUN_TEST_REPLAY,
  PUBLISH_WORKFLOW;

  /** The stored key (Example: {@code approve_action}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
