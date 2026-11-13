// DC-S04 Acknowledge page (SL-A4-04) at /m/acknowledge/{incident id} (AR-I-101; P-30 spec Sections 6.3, 6.4): reads
// AR-I-32, polled every 1 s and re-read on incident:{id} (AR-I-04, AR-I-05); "Acknowledge" sends AR-I-33 without
// confirmation.
import { useQuery } from "@tanstack/react-query";
import { useParams } from "react-router";
import type { AcknowledgePage as Page } from "../api/types";
import { SeverityBadge, StateChip } from "../badges/Badges";
import { incidentNumber } from "../format/names";
import { viewerTime } from "../format/time";
import { MobileLayout } from "../layout/MobileLayout";
import { actionArea, type Local } from "./actionArea";
import { sendAcknowledgement } from "./decision";
import styles from "./mobile.module.css";
import { ActionArea, ReadState, Value } from "./parts";
import { acknowledgeQuery, acknowledgeTopics } from "./queries";
import {
  acknowledgedText,
  acknowledgeRefusalText,
  type TimeFormat,
} from "./refusals";
import { useAction } from "./useAction";

export const ACKNOWLEDGE_DENIED =
  "You do not have permission to acknowledge incidents.";

const browserTime: TimeFormat = (iso) => viewerTime(iso);

export function AcknowledgePage() {
  const { incidentId = "" } = useParams();
  return <Acknowledge key={incidentId} id={incidentId} />;
}

function Acknowledge({ id }: { id: string }) {
  const options = acknowledgeQuery(id);
  const query = useQuery(options);
  const { local, run } = useAction(query.data, () => query.refetch());
  return (
    <MobileLayout
      screen="Acknowledge"
      topics={acknowledgeTopics(id)}
      queryKey={options.queryKey}
    >
      <ReadState
        data={query.data}
        error={query.isError ? query.error : null}
        onTryAgain={() => void query.refetch()}
        denied={ACKNOWLEDGE_DENIED}
      >
        {(page) => (
          <AcknowledgeView
            page={page}
            local={local}
            format={browserTime}
            onAcknowledge={() =>
              void run(
                () => sendAcknowledgement(id),
                (answer) => ({
                  text: acknowledgedText(answer, browserTime),
                  tone: "success",
                  refusalKey: "already_acknowledged",
                }),
                "acknowledge",
              )
            }
          />
        )}
      </ReadState>
    </MobileLayout>
  );
}

/** The content card (DC-S04-03) and the action area (DC-S04-04, DC-S04-05). */
export function AcknowledgeView({
  page,
  local,
  format,
  onAcknowledge,
}: {
  page: Page;
  local: Local;
  format: TimeFormat;
  onAcknowledge: () => void;
}) {
  const area = actionArea(page, acknowledgeRefusalText(page, format), local);
  return (
    <>
      <div className={styles.card}>
        <h1 className={styles.heading}>Acknowledge incident</h1>
        <p className={styles.incident}>
          <span className={styles.number}>{incidentNumber(page.number)}</span>{" "}
          {page.title}
        </p>
        <Value label="Severity:">
          <SeverityBadge severity={page.severity} />
        </Value>
        <Value label="Owner:">{page.owner?.name ?? ""}</Value>
        <Value label="Detected:">
          <span className={styles.time}>
            {format(page.first_alert_fired_at)}
          </span>
        </Value>
        <Value label="Alerts:">{page.alert_count}</Value>
        <Value label="Summary:">{page.summary ?? ""}</Value>
        <Value label="State:">
          <StateChip state={page.state} />
        </Value>
      </div>
      <ActionArea
        area={area}
        buttons={
          <button
            type="button"
            className={styles.button}
            onClick={onAcknowledge}
            disabled={local.sending}
          >
            Acknowledge
          </button>
        }
      />
    </>
  );
}
