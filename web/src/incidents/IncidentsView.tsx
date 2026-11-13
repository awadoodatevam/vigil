// DC-S02 The incident list's content and its states (P-28 spec Section 6.10): PageError when both parts fail, else
// PartError in the part that failed; the empty text needs the strip's total, so the table waits for the strip.
import type { AlertStrip, IncidentRow } from "../api/types";
import { count } from "../format/names";
import {
  ActionFailed,
  Empty,
  Loading,
  PageError,
  PartError,
} from "../states/states";
import styles from "./incidents.module.css";
import { Rows } from "./Rows";
import { Strip } from "./Strip";

export interface StripView {
  data: AlertStrip | undefined;
  /** The reason when the strip's latest read failed. */
  failure: string | null;
}

export interface RowsView {
  rows: readonly IncidentRow[] | undefined;
  hasMore: boolean;
  /** The reason when the rows' latest read (not "Show more") failed. */
  failure: string | null;
  /** The reason when "Show more" failed. */
  moreFailure: string | null;
  loadingMore: boolean;
}

export interface IncidentsViewProps {
  product: string;
  strip: StripView;
  rows: RowsView;
  onTryAgainStrip: () => void;
  onTryAgainRows: () => void;
  onShowMore: () => void;
  onOpen: (incidentId: string) => void;
  now?: Date;
  zone?: string;
}

export function emptyText(product: string, total: number): string {
  return `No incidents yet. ${product} is watching ${count(total, "monitoring source", "monitoring sources")}.`;
}

export function IncidentsView(props: IncidentsViewProps) {
  const { strip, rows } = props;
  if (strip.failure !== null && rows.failure !== null) {
    return (
      <PageError
        reason={strip.failure}
        onTryAgain={() => {
          props.onTryAgainStrip();
          props.onTryAgainRows();
        }}
      />
    );
  }
  return (
    <>
      <h1>Incidents</h1>
      {strip.failure !== null ? (
        <PartError reason={strip.failure} onTryAgain={props.onTryAgainStrip} />
      ) : strip.data === undefined ? (
        <PartLoading />
      ) : (
        <Strip strip={strip.data} now={props.now} zone={props.zone} />
      )}
      <RowsPart {...props} />
    </>
  );
}

function RowsPart(props: IncidentsViewProps) {
  const { strip, rows } = props;
  if (rows.failure !== null) {
    return (
      <PartError reason={rows.failure} onTryAgain={props.onTryAgainRows} />
    );
  }
  if (rows.rows === undefined) {
    return <PartLoading />;
  }
  if (rows.rows.length === 0 && !rows.hasMore) {
    if (strip.failure !== null || strip.data === undefined) {
      return <PartLoading />;
    }
    return <Empty text={emptyText(props.product, strip.data.sources.total)} />;
  }
  return (
    <div className={styles.tableCard}>
      <Rows
        rows={rows.rows}
        onOpen={props.onOpen}
        now={props.now}
        zone={props.zone}
      />
      {rows.hasMore && (
        <div className={styles.more}>
          <button
            type="button"
            onClick={props.onShowMore}
            disabled={rows.loadingMore}
          >
            Show more
          </button>
          {rows.moreFailure !== null && (
            <ActionFailed action="show more" reason={rows.moreFailure} />
          )}
        </div>
      )}
    </div>
  );
}

/** A part's Loading in a card, as the part it replaces (VD-C-13). */
function PartLoading() {
  return (
    <div className={styles.partCard}>
      <Loading />
    </div>
  );
}
