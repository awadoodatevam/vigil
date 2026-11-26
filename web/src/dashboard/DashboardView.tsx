// DC-S10 The dashboard's content and its states (P-33 spec Sections 6.3, 6.4): the heading row with "Refresh", the four
// tiles and the data line; Loading in place of the tiles and the data line while a read runs; PageError in place of the
// whole content when the latest read failed.
import type { Dashboard } from "../api/types";
import { Loading, PageError } from "../states/states";
import { KindTag } from "../tags/KindTag";
import styles from "./dashboard.module.css";
import { dataLine, METRICS, metricText, type Metric } from "./texts";

export interface DashboardViewProps {
  /** The latest answer of AR-I-80. */
  data: Dashboard | undefined;
  /** Whether a read runs (the opening read, "Refresh", "Try again"). */
  fetching: boolean;
  /** The reason when the latest read failed. */
  failure: string | null;
  onRefresh: () => void;
}

export function DashboardView(props: DashboardViewProps) {
  if (!props.fetching && props.failure !== null) {
    return <PageError reason={props.failure} onTryAgain={props.onRefresh} />;
  }
  const data = props.fetching ? undefined : props.data;
  return (
    <>
      <div className={styles.headingRow}>
        <h1>Dashboard</h1>
        <button
          type="button"
          className={styles.refresh}
          onClick={props.onRefresh}
          disabled={props.fetching}
        >
          Refresh
        </button>
      </div>
      {data === undefined ? (
        <div className={styles.loadingCard}>
          <Loading />
        </div>
      ) : (
        <>
          <div className={styles.tiles}>
            {METRICS.map((tile) => (
              <Tile
                key={tile.key}
                id={`metric-${tile.key}`}
                name={tile.name}
                definition={tile.definition}
                metric={data.metrics.find((metric) => metric.key === tile.key)}
              />
            ))}
          </div>
          <p className={styles.dataLine}>
            {dataLine(
              data.live_incidents,
              data.past_incidents,
              data.record_labels,
            )}
          </p>
        </>
      )}
    </>
  );
}

interface TileProps {
  /** The id of the tile's name, which labels the tile. */
  id: string;
  name: string;
  definition: string;
  metric: Metric | undefined;
}

function Tile({ id, name, definition, metric }: TileProps) {
  const value = metric?.value ?? null;
  return (
    <section className={styles.tile} aria-labelledby={id}>
      <h2 id={id} className={styles.name}>
        {name}
      </h2>
      <p className={value === null ? styles.noData : styles.value}>
        {metricText(value, metric?.unit ?? "count")}
      </p>
      <p className={styles.definition}>{definition}</p>
      {metric !== undefined && metric.baseline_value !== null && (
        <p className={styles.baseline}>
          <span className={styles.baselineValue}>
            {metricText(metric.baseline_value, metric.unit)}
          </span>{" "}
          <KindTag word="Illustrative baseline" />
        </p>
      )}
    </section>
  );
}
