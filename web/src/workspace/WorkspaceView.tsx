// DC-S03 The incident workspace's content (VD-L-05, VD-S-05; P-29 spec Sections 6.3 to 6.7): the header card, the run
// band, then the main column (anchors and sections) and the side column. Each part shows its own read's state.
import type { ReactNode } from "react";
import type {
  EvidenceLines,
  IncidentAlerts,
  IncidentHeader,
  IncidentTimeline,
  InvestigationSection,
  LiveRun,
  NotifiedTeams,
  RecoverySection,
  ResponseSection,
} from "../api/types";
import { RunBand } from "../canvas/RunCanvas";
import { AlertsContent } from "./Alerts";
import { EvidenceContent } from "./Evidence";
import { WorkspaceHeader } from "./Header";
import { HypothesisContent } from "./Hypothesis";
import { InvestigationContent } from "./Investigation";
import { useOpenSet } from "./lines";
import { RecoveryContent } from "./Recovery";
import { ResponseContent } from "./Response";
import { partContent, Section, type PartState } from "./Section";
import { NotifiedContent, RunStepsContent, TimelineContent } from "./Side";
import styles from "./workspace.module.css";

export interface WorkspaceParts {
  alerts: PartState<IncidentAlerts>;
  evidence: PartState<EvidenceLines>;
  investigation: PartState<InvestigationSection>;
  response: PartState<ResponseSection>;
  recovery: PartState<RecoverySection>;
  run: PartState<LiveRun | null>;
  timeline: PartState<IncidentTimeline>;
  notified: PartState<NotifiedTeams>;
}

export interface WorkspaceViewProps {
  incident: IncidentHeader;
  parts: WorkspaceParts;
  product: string;
  /** False in markup tests: the band without the React Flow subtree. */
  withCanvas?: boolean;
}

/** Which parts are shown (Section 6.3 item 3): a part with no empty text only once it has content or failed. */
export function shownParts(parts: WorkspaceParts) {
  const { run, response, recovery } = parts;
  return {
    run: run.failure !== null || run.data !== null,
    response:
      response.failure !== null ||
      (response.data !== undefined && response.data.similar !== null),
    recovery:
      recovery.failure !== null ||
      (recovery.data !== undefined && recovery.data.verifications.length > 0),
  };
}

/** The main column's sections in order, with their headings and anchor ids. */
export function mainSections(incident: IncidentHeader, parts: WorkspaceParts) {
  const shown = shownParts(parts);
  const evidence = parts.evidence;
  const evidenceKnown = evidence.failure === null && evidence.data;
  return [
    { id: "hypothesis", heading: "Hypothesis and customer impact" },
    { id: "alerts", heading: `Alerts (${incident.alert_count})` },
    {
      id: "evidence",
      heading: evidenceKnown
        ? `Evidence (${evidenceKnown.entries.length})`
        : "Evidence",
    },
    { id: "ai-investigation", heading: "AI investigation" },
    ...(shown.response ? [{ id: "response", heading: "Response" }] : []),
    ...(shown.recovery ? [{ id: "recovery", heading: "Recovery" }] : []),
  ];
}

export function WorkspaceView({
  incident,
  parts,
  product,
  withCanvas = true,
}: WorkspaceViewProps) {
  const [groupOpen, toggleGroup] = useOpenSet();
  const [reasonOpen, toggleReason] = useOpenSet();
  const [evidenceOpen, toggleEvidence] = useOpenSet();
  const [entryOpen, toggleEntry] = useOpenSet();
  const sections = mainSections(incident, parts);
  const content: Record<string, ReactNode> = {
    hypothesis: <HypothesisContent incident={incident} />,
    alerts: partContent(parts.alerts, (alerts) => (
      <AlertsContent
        alerts={alerts}
        groups={{ isOpen: groupOpen, toggle: toggleGroup }}
        reasons={{ isOpen: reasonOpen, toggle: toggleReason }}
      />
    )),
    evidence: partContent(parts.evidence, (evidence) => (
      <EvidenceContent
        evidence={evidence}
        open={{ isOpen: evidenceOpen, toggle: toggleEvidence }}
      />
    )),
    "ai-investigation": partContent(parts.investigation, (section) => (
      <InvestigationContent section={section} />
    )),
    response: partContent(parts.response, (section) => (
      <ResponseContent section={section} />
    )),
    recovery: partContent(parts.recovery, (section) => (
      <RecoveryContent section={section} />
    )),
  };
  return (
    <div className={styles.workspace}>
      <WorkspaceHeader incident={incident} />
      {shownParts(parts).run && (
        <RunBand part={parts.run} withCanvas={withCanvas} />
      )}
      <div className={styles.columns}>
        <div className={styles.column}>
          <nav className={styles.anchors} aria-label="Sections">
            {sections.map((section) => (
              <a key={section.id} href={`#${section.id}`}>
                {section.heading}
              </a>
            ))}
          </nav>
          {sections.map((section) => (
            <Section key={section.id} id={section.id} heading={section.heading}>
              {content[section.id]}
            </Section>
          ))}
        </div>
        <div className={styles.column}>
          <Section heading="Run steps">
            {partContent(parts.run, (run) => (
              <RunStepsContent run={run} />
            ))}
          </Section>
          <Section heading="Timeline">
            {partContent(parts.timeline, (timeline) => (
              <TimelineContent
                timeline={timeline}
                product={product}
                open={{ isOpen: entryOpen, toggle: toggleEntry }}
              />
            ))}
          </Section>
          <Section heading="Notified">
            {partContent(parts.notified, (notified) => (
              <NotifiedContent notified={notified} />
            ))}
          </Section>
        </div>
      </div>
    </div>
  );
}
