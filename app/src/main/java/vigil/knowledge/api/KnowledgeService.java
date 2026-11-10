package vigil.knowledge.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** Runbooks, past incidents and the similar-incident search (PM-M16). */
public interface KnowledgeService {

  /**
   * PM-M16-03: the search with the settings' embedding provider, its result stored.
   *
   * <p>external call: not inside a transaction
   */
  SearchResult search(SearchRequest request);

  /**
   * PM-M16-02, DM-R-50: at most runbook_limit runbooks in the order of DM-R-50; componentKind null
   * for none.
   */
  List<RunbookView> applicableRunbooks(
      TenantId tenantId, Collection<String> alertTypes, String componentKind);

  /** By runbook_key, then version. */
  List<RunbookView> runbooks(TenantId tenantId);

  /** By past_incident_key. */
  List<PastIncidentView> pastIncidents(TenantId tenantId);

  Optional<PastIncidentView> pastIncident(TenantId tenantId, String pastIncidentKey);

  Optional<KnowledgeSettingsView> settings(TenantId tenantId);

  /** DM-E66-R2: the incident's latest result (by searched_at, then id). */
  Optional<SimilarityResultView> latestSearchResult(TenantId tenantId, UUID incidentId);
}
