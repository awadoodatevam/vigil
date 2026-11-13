// DC-S03 Incident workspace (SL-A4-03) at /incidents/{incident id}, for `view` (P-29 spec Sections 6.2, 6.3): reads
// AR-I-22 first; a merged incident opens the incident it was merged into; the topics incident:{id}, run:{id} and
// alerts (AR-I-04) and the recovery polling (AR-I-05) keep it current.
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { useEffect, type ReactNode } from "react";
import { useNavigate, useParams } from "react-router";
import { reasonOf } from "../api/client";
import type { IncidentHeader, LiveRun, MergedIncident } from "../api/types";
import { incidentNumber } from "../format/names";
import { SignedInLayout } from "../layout/SignedInLayout";
import { brandingQuery, tenantQuery } from "../session/queries";
import { Loading, PageError } from "../states/states";
import {
  alertsQuery,
  evidenceQuery,
  headerQuery,
  investigationQuery,
  notifiedQuery,
  recoveryQuery,
  responseQuery,
  runQuery,
  timelineQuery,
} from "./queries";
import type { PartState } from "./Section";
import styles from "./workspace.module.css";
import { WorkspaceView } from "./WorkspaceView";

/** The address of the incident a merged incident was merged into (DC-S03-01); null for an incident. */
export function mergedAddress(
  answer: IncidentHeader | MergedIncident | undefined,
): string | null {
  return answer !== undefined && "merged_into_incident_id" in answer
    ? `/incidents/${answer.merged_into_incident_id}`
    : null;
}

/** The incident of an answer of AR-I-22, or undefined. */
export function incidentOf(
  answer: IncidentHeader | MergedIncident | undefined,
): IncidentHeader | undefined {
  return answer !== undefined && "incident_id" in answer ? answer : undefined;
}

/** "{number} {title}" once AR-I-22 has answered an incident, else null (DC-U-15). */
export function screenName(
  header: Pick<
    UseQueryResult<IncidentHeader | MergedIncident>,
    "data" | "isSuccess"
  >,
): string | null {
  const incident = header.isSuccess ? incidentOf(header.data) : undefined;
  return incident
    ? `${incidentNumber(incident.number)} ${incident.title}`
    : null;
}

/** The live topics of the workspace (AR-I-04 row DC-S03), besides `tenant`. */
export function workspaceTopics(
  incidentId: string | undefined,
  runId: string | undefined,
): string[] {
  return [
    ...(incidentId ? [`incident:${incidentId}`] : []),
    ...(runId ? [`run:${runId}`] : []),
    "alerts",
  ];
}

export function WorkspacePage() {
  const { incidentId = "" } = useParams();
  return <Workspace key={incidentId} id={incidentId} />;
}

function partOf<T, R = T>(
  query: UseQueryResult<T>,
  map: (data: T) => R = (data) => data as unknown as R,
): PartState<R> {
  return {
    data: query.data === undefined ? undefined : map(query.data),
    failure: query.isError ? reasonOf(query.error) : null,
    onTryAgain: () => void query.refetch(),
  };
}

function Workspace({ id }: { id: string }) {
  const navigate = useNavigate();
  const header = useQuery(headerQuery(id));
  const run = useQuery(runQuery(id));
  const merged = mergedAddress(header.data);
  useEffect(() => {
    if (merged !== null) {
      void navigate(merged, { replace: true });
    }
  }, [merged, navigate]);
  const incident = incidentOf(header.data);
  const topics = workspaceTopics(
    incident?.incident_id,
    run.data?.run?.run_id ?? undefined,
  );
  return (
    <SignedInLayout
      access="view"
      screen={screenName(header)}
      topics={topics}
      live
    >
      <PageBody
        incident={incident}
        failure={header.isError ? reasonOf(header.error) : null}
        onTryAgain={() => void header.refetch()}
      >
        {incident && (
          <Parts id={id} incident={incident} run={partOf(run, (d) => d.run)} />
        )}
      </PageBody>
    </SignedInLayout>
  );
}

/** AR-I-22 first (Section 6.3 item 1): PageError when its latest read failed, Loading in a card until an incident. */
export function PageBody({
  incident,
  failure,
  onTryAgain,
  children,
}: {
  incident: IncidentHeader | undefined;
  failure: string | null;
  onTryAgain: () => void;
  children: ReactNode;
}) {
  if (failure !== null) {
    return <PageError reason={failure} onTryAgain={onTryAgain} />;
  }
  if (incident === undefined) {
    return (
      <div className={styles.loadingCard}>
        <Loading />
      </div>
    );
  }
  return children;
}

function Parts({
  id,
  incident,
  run,
}: {
  id: string;
  incident: IncidentHeader;
  run: PartState<LiveRun | null>;
}) {
  const branding = useQuery(brandingQuery);
  const tenant = useQuery(tenantQuery);
  const product = (tenant.data ?? branding.data)?.product_display_name ?? "";
  const parts = {
    alerts: partOf(useQuery(alertsQuery(id))),
    evidence: partOf(useQuery(evidenceQuery(id))),
    investigation: partOf(useQuery(investigationQuery(id))),
    response: partOf(useQuery(responseQuery(id))),
    recovery: partOf(useQuery(recoveryQuery(id))),
    run,
    timeline: partOf(useQuery(timelineQuery(id))),
    notified: partOf(useQuery(notifiedQuery(id))),
  };
  return <WorkspaceView incident={incident} parts={parts} product={product} />;
}
