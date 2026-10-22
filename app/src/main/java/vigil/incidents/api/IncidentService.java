package vigil.incidents.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vigil.shared.Actor;
import vigil.shared.Key;
import vigil.shared.TenantId;

/**
 * Incidents and their records (PM-M09). Each request that changes an incident takes the actor of
 * the requesting module (DM-E32-R1), which records the change; other actors are refused with {@code
 * IllegalArgumentException}. A request for an incident the tenant does not have is {@code
 * NOT_FOUND}; a request in a state it is not allowed in is an {@code IllegalStateException}.
 */
public interface IncidentService {

  /** DM-SM05-T1, from vigil-correlation: a {@code forming} incident with its first member. */
  UUID create(
      TenantId tenantId,
      Actor actor,
      UUID groupingDecisionId,
      AlertCopy alert,
      GroupingReason reason);

  /** From vigil-correlation: an alert joins a {@code forming} or {@code open} incident. */
  void addAlert(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      UUID groupingDecisionId,
      AlertCopy alert,
      GroupingReason reason);

  /** DM-SM05-T3, DM-R-12, from vigil-correlation: {@code forming} incidents merged into one. */
  void merge(TenantId tenantId, Actor actor, UUID survivorId, List<UUID> absorbedIds);

  /**
   * From vigil-correlation: the hypothesis (both {@code null} for none) and the customer impact.
   */
  void setHypothesisAndImpact(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      HypothesisCopy hypothesis,
      UUID hypothesisComputationId,
      List<AffectedServiceCopy> affectedServices);

  /** DM-SM05-T2, from vigil-correlation, with the final reason of each active member. */
  void open(TenantId tenantId, Actor actor, UUID incidentId, Map<UUID, FinalReason> finalReasons);

  /** DM-SM05-T5, DM-R-21, from vigil-correlation: a new alert reopens a {@code resolved} one. */
  void reopen(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      UUID groupingDecisionId,
      AlertCopy alert,
      GroupingReason reason);

  /** PM-M09-03, from vigil-routing: the assignment of a {@code forming} incident. */
  void setAssignment(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      Severity severity,
      Key ownerTeamKey,
      Set<Key> informedTeamKeys,
      UUID routingEvaluationId,
      String ruleName);

  /** PM-M09-08, from vigil-routing: an escalation record (DM-E-36); returns its id. */
  UUID recordEscalation(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      long level,
      Set<Key> teamKeys,
      EscalationCause cause,
      Duration timer,
      long policyRevision);

  /** PM-M09-08, from vigil-notifications: a notified team (DM-E-37), once per team. */
  void recordNotifiedTeam(
      TenantId tenantId, Actor actor, UUID incidentId, Key teamKey, NotifiedPurpose purpose);

  /**
   * PM-M09-05, from the producer of the kind: an evidence entry (DM-E-38); returns its id, or the
   * id of the entry already stored for the producer's record (DM-R-65).
   */
  UUID addEvidence(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      EvidenceKind kind,
      UUID producerRecordId,
      String sourceSystem,
      Instant collectedAt,
      String summary,
      String contentJson,
      DataCategory dataCategory);

  /** PM-M09-07, from vigil-investigation: the AI status and the latest request group. */
  void setAiStatus(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      AiStatus status,
      AiUnavailableReason reason,
      UUID requestGroupId);

  /** DM-SM05-T4, PM-M09-06, from vigil-recovery, with the verification's evidence entry. */
  void resolve(TenantId tenantId, Actor actor, UUID incidentId, UUID verificationId);

  /**
   * PM-M09-04: the signed-in user's acknowledgement (DM-E35-R1); refusals are {@code FORBIDDEN} or
   * {@code REFUSED} with details {@code refusal} {@code not_open}, {@code already_acknowledged} or
   * {@code not_notified}.
   */
  AcknowledgementView acknowledge(TenantId tenantId, UUID incidentId, UUID userId);

  Optional<IncidentView> incident(TenantId tenantId, UUID incidentId);

  /** The incidents in the given states, by ascending {@code sequence}. */
  List<IncidentView> incidents(TenantId tenantId, Set<IncidentState> states);

  /** The incident of the alert's active membership. */
  Optional<UUID> incidentOfAlert(TenantId tenantId, UUID alertId);

  /** The active memberships, by {@code joined_at}, then id. */
  List<MembershipView> members(TenantId tenantId, UUID incidentId);

  /** Every membership, active or ended, by {@code joined_at}, then id. */
  List<MembershipView> memberships(TenantId tenantId, UUID incidentId);

  List<TransitionView> transitions(TenantId tenantId, UUID incidentId);

  /** The acknowledgement of the current episode. */
  Optional<AcknowledgementView> acknowledgement(TenantId tenantId, UUID incidentId);

  /** The acknowledgement of every episode, by ascending episode. */
  List<AcknowledgementView> acknowledgements(TenantId tenantId, UUID incidentId);

  List<EscalationView> escalations(TenantId tenantId, UUID incidentId);

  /** By {@code first_notified_at}, then team key. */
  List<NotifiedTeamView> notifiedTeams(TenantId tenantId, UUID incidentId);

  /** By {@code collected_at}, then id. */
  List<EvidenceView> evidence(TenantId tenantId, UUID incidentId);

  /** PM-M09-11: the evidence summaries in {@code collected_at} order. */
  List<String> evidenceSummary(TenantId tenantId, UUID incidentId);

  /** By {@code at}, then id. */
  List<TimelineView> timeline(TenantId tenantId, UUID incidentId);

  Optional<IncidentSettingsView> settings(TenantId tenantId);
}
