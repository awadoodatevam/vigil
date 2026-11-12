package vigil.actions.api;

/** An action policy (DM-E-56); {@code serviceKey} is {@code null} for every service. */
public record ActionPolicyView(
    String actionKey, String serviceKey, PolicyOutcome outcome, boolean active) {}
