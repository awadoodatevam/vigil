// DC-S05 Approval page (SL-A4-05) at /m/approve/{approval request id} (AR-I-102; P-30 spec Sections 6.3, 6.5): reads
// AR-I-34, polled every 1 s while pending and re-read on approval:{id} and incident:{id} (AR-I-04, AR-I-05);
// "Approve" and "Reject" each ask for confirmation (DC-U-11) and send AR-I-35 with the snapshot hash shown.
import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { useParams } from "react-router";
import type { ApprovalPage as Page } from "../api/types";
import { ConfirmDialog } from "../dialog/ConfirmDialog";
import { duration } from "../format/duration";
import {
  approvalStateName,
  count,
  EVIDENCE_KIND_WORDS,
  incidentNumber,
  valueText,
} from "../format/names";
import { expiresIn, viewerTime } from "../format/time";
import { MobileLayout } from "../layout/MobileLayout";
import { brandingQuery, tenantQuery } from "../session/queries";
import { KindTag } from "../tags/KindTag";
import { useNow } from "../workspace/useNow";
import { actionArea, type Local } from "./actionArea";
import { sendDecision, type DecisionKind } from "./decision";
import styles from "./mobile.module.css";
import { ActionArea, ReadState, Value } from "./parts";
import { approvalQuery, approvalTopics } from "./queries";
import { decidedText, decisionRefusalText, type TimeFormat } from "./refusals";
import { useAction } from "./useAction";

export const APPROVE_DENIED = "You do not have permission to approve actions.";

const browserTime: TimeFormat = (iso) => viewerTime(iso);

/** `{target name}` of DC-U-11: the target's name, or its key when the name is null (CTX-D-091 (5)). */
export function targetName(page: Pick<Page, "target">): string {
  return page.target.name ?? page.target.target_key;
}

/** The question of DC-U-11 for the decision. */
export function confirmQuestion(page: Page, decision: DecisionKind): string {
  const verb = decision === "approve" ? "Approve" : "Reject";
  return `${verb} ${page.action_name} on ${targetName(page)}?`;
}

export function ApprovalPage() {
  const { requestId = "" } = useParams();
  return <Approval key={requestId} id={requestId} />;
}

function Approval({ id }: { id: string }) {
  const options = approvalQuery(id);
  const query = useQuery(options);
  const branding = useQuery(brandingQuery);
  const tenant = useQuery(tenantQuery);
  const product = (tenant.data ?? branding.data)?.product_display_name ?? "";
  const { local, run } = useAction(query.data, () => query.refetch());
  const [dialog, setDialog] = useState<{
    decision: DecisionKind;
    open: boolean;
  }>({ decision: "approve", open: false });
  const now = useNow(query.data?.state === "pending");

  function confirm(page: Page) {
    const decision = dialog.decision;
    setDialog({ decision, open: false });
    void run(
      () => sendDecision(id, decision, page.snapshot_hash),
      (answer) => ({
        text: decidedText(answer, product, browserTime),
        tone: answer.state === "approved" ? "success" : "info",
        refusalKey: "already_decided",
      }),
      decision,
    );
  }

  return (
    <MobileLayout
      screen="Approve"
      topics={approvalTopics(id, query.data?.incident.incident_id)}
      queryKey={options.queryKey}
    >
      <ReadState
        data={query.data}
        error={query.isError ? query.error : null}
        onTryAgain={() => void query.refetch()}
        denied={APPROVE_DENIED}
      >
        {(page) => (
          <ApprovalView
            page={page}
            local={local}
            now={now}
            format={browserTime}
            onPress={(decision) => setDialog({ decision, open: true })}
          >
            <ConfirmDialog
              question={confirmQuestion(page, dialog.decision)}
              confirmLabel={
                dialog.decision === "approve" ? "Approve" : "Reject"
              }
              tone={dialog.decision === "approve" ? "primary" : "destructive"}
              open={dialog.open}
              onConfirm={() => confirm(page)}
              onCancel={() =>
                setDialog((current) => ({ ...current, open: false }))
              }
            />
          </ApprovalView>
        )}
      </ReadState>
    </MobileLayout>
  );
}

function Part({ heading, children }: { heading: string; children: ReactNode }) {
  return (
    <section>
      <h2 className={styles.part}>{heading}</h2>
      {children}
    </section>
  );
}

function Line({ children }: { children: ReactNode }) {
  return <p className={styles.line}>{children}</p>;
}

/** The content card (DC-S05-03) and the action area (DC-S05-04 to DC-S05-06). */
export function ApprovalView({
  page,
  local,
  now,
  format,
  onPress,
  children,
}: {
  page: Page;
  local: Local;
  /** The viewer's clock, for the countdown (CTX-D-079 (1)). */
  now: number;
  format: TimeFormat;
  onPress: (decision: DecisionKind) => void;
  /** The confirmation dialog. */
  children?: ReactNode;
}) {
  const area = actionArea(page, decisionRefusalText(page, format), local);
  const countdown =
    page.state === "pending" ? expiresIn(page.expires_at, now) : null;
  const { based_on: basedOn, recovery_checks: checks } = page;
  return (
    <>
      <div className={styles.card}>
        <h1 className={styles.heading}>Approve action</h1>
        {page.sequence_in_run > 1 && (
          <p className={styles.info}>
            Approval {page.sequence_in_run}: your approval must differ from
            earlier approvals in this run.
          </p>
        )}
        <p className={styles.incident}>
          <span className={styles.number}>
            {page.incident.number === null
              ? ""
              : incidentNumber(page.incident.number)}
          </span>{" "}
          {page.incident.title ?? ""}
        </p>
        <Value label="State:">
          {approvalStateName(page.state, page.refusal_reason)}
        </Value>
        <Part heading="Action">
          <Line>{page.action_name}</Line>
        </Part>
        <Part heading="Parameters">
          {page.parameters.map((parameter) => (
            <p key={parameter.name} className={styles.param}>
              {parameter.name} = {valueText(parameter.value)}
            </p>
          ))}
        </Part>
        <Part heading="Target server">
          <Line>
            {page.target.name === null
              ? page.target.target_key
              : `${page.target.name} (${page.target.target_key})`}
          </Line>
        </Part>
        <Part heading="Limits">
          <ul className={styles.limits}>
            {page.limits.map((limit, index) => (
              <li key={index}>{limit}</li>
            ))}
          </ul>
          <Line>
            Attempt {page.attempt_number} of {page.attempt_limit}
          </Line>
        </Part>
        <Part heading="Expires">
          <p className={`${styles.line} ${styles.time}`}>
            {format(page.expires_at)}
          </p>
          {countdown !== null && (
            <p className={styles.countdown}>{countdown}</p>
          )}
        </Part>
        <Part heading="Based on">
          <p className={styles.tagged}>
            <span>
              {basedOn.past_incident_key} {basedOn.past_incident_title}
            </span>
            <KindTag recordLabel={basedOn.record_label} />
          </p>
          <Line>Similarity {basedOn.score}</Line>
          <Line>
            Fix succeeded {count(basedOn.success_count, "time", "times")}
          </Line>
          <ul className={styles.evidence}>
            {page.evidence.map((entry) => (
              <li key={entry.evidence_entry_id}>
                <p className={styles.tagged}>
                  <KindTag word={EVIDENCE_KIND_WORDS[entry.kind]} />
                  <span>{entry.summary}</span>
                </p>
                <p className={styles.collected}>{format(entry.collected_at)}</p>
              </li>
            ))}
          </ul>
        </Part>
        <Part heading="Recovery checks that will be used">
          <Line>Check: {checks.name}</Line>
          {checks.conditions.map((condition, index) => (
            <Line key={index}>{condition}</Line>
          ))}
          <Line>
            Held for {duration(checks.observation_window_seconds)} while every
            monitoring source is healthy.
          </Line>
        </Part>
      </div>
      <ActionArea
        area={area}
        buttons={
          <>
            <button
              type="button"
              className={styles.button}
              onClick={() => onPress("approve")}
              disabled={local.sending}
            >
              Approve
            </button>
            <button
              type="button"
              className={`${styles.button} ${styles.destructive}`}
              onClick={() => onPress("reject")}
              disabled={local.sending}
            >
              Reject
            </button>
          </>
        }
      >
        {children}
      </ActionArea>
    </>
  );
}
