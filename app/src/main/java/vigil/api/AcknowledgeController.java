package vigil.api;

import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.AcknowledgementView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.investigation.api.IncidentSummaryView;
import vigil.investigation.api.InvestigationService;
import vigil.servicemap.api.ServiceMapService;

/** AR-I-32 the Acknowledge page (DC-S04) and AR-I-33 the acknowledgement (DC-S04-04). */
@RestController
@Profile("!migrate")
class AcknowledgeController {

  /** The refusals that depend on the user (CTX-D-079 (2)). */
  private static final Set<String> PERSONAL = Set.of("not_notified");

  private final IncidentService incidents;
  private final InvestigationService investigation;
  private final IdentityService identity;
  private final ServiceMapService serviceMap;

  AcknowledgeController(
      IncidentService incidents,
      InvestigationService investigation,
      IdentityService identity,
      ServiceMapService serviceMap) {
    this.incidents = incidents;
    this.investigation = investigation;
    this.identity = identity;
    this.serviceMap = serviceMap;
  }

  @GetMapping("/api/v1/incidents/{incident_id}/acknowledgement")
  @RequiresPermission({Permission.ACKNOWLEDGE, Permission.VIEW})
  AcknowledgePage page(ApiCaller caller, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, caller.tenantId(), incidentId);
    Names names = new Names(identity, serviceMap, caller.tenantId());
    boolean mayAct =
        identity.hasPermission(caller.tenantId(), caller.user().id(), Permission.ACKNOWLEDGE);
    Refusal refusal =
        incidents
            .acknowledgeRefusal(caller.tenantId(), incident.id(), caller.user().id())
            .filter(r -> mayAct || !PERSONAL.contains(r.key()))
            .map(r -> new Refusal(r.key(), r.message()))
            .orElse(null);
    IncidentPage.Acknowledged acknowledged =
        incident.acknowledgedBy() == null
            ? null
            : new IncidentPage.Acknowledged(
                names.user(incident.acknowledgedBy()), ApiTimes.format(incident.acknowledgedAt()));
    return new AcknowledgePage(
        incident.id().toString(),
        incident.number(),
        incident.title(),
        incident.severity() == null ? null : incident.severity().key(),
        incident.ownerTeamKey() == null ? null : names.team(incident.ownerTeamKey()),
        ApiTimes.format(incident.firstAlertFiredAt()),
        incident.alertCount(),
        investigation
            .latestSummary(caller.tenantId(), incident.id())
            .map(IncidentSummaryView::text)
            .orElse(null),
        incident.state().key(),
        acknowledged,
        mayAct,
        refusal);
  }

  @PostMapping("/api/v1/incidents/{incident_id}/acknowledgements")
  @RequiresPermission(Permission.ACKNOWLEDGE)
  ResponseEntity<NewAcknowledgement> acknowledge(
      ApiCaller caller, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, caller.tenantId(), incidentId);
    AcknowledgementView done =
        incidents.acknowledge(caller.tenantId(), incident.id(), caller.user().id());
    Names names = new Names(identity, serviceMap, caller.tenantId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new NewAcknowledgement(names.user(done.userId()), ApiTimes.format(done.at())));
  }
}
