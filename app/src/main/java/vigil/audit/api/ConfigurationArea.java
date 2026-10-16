package vigil.audit.api;

import java.util.Locale;

/** The configuration areas of DM-V-11 (complete list, in its order). */
public enum ConfigurationArea {
  TENANT_SETTINGS,
  USERS_AND_TEAMS,
  SOURCES,
  SERVICE_MAP,
  ROUTING_RULES,
  ESCALATION_LEVELS,
  WORKFLOWS,
  AI_SETTINGS,
  REGISTERED_ACTIONS,
  SEED_IMPORT;

  /** The stored key (Example: {@code users_and_teams}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
