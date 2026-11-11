package vigil.notifications.api;

import vigil.shared.Key;

/** A user who receives an approval request, and the team through which it was chosen. */
public record ApprovalRecipient(Key username, Key viaTeamKey) {}
