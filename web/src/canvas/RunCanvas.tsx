// The run band (DC-S03-06, AR-W-03, VD-C-18; P-29 spec Section 6.6): the latest live run on a read-only canvas, with
// follow mode, zoom, Fit and Expand. The same band, React Flow instance and follow state become the expanded card.
import "@xyflow/react/dist/base.css";
import {
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
  type ReactFlowProps,
} from "@xyflow/react";
import {
  use,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type { LiveRun } from "../api/types";
import { StageChip } from "../badges/Badges";
import { runStateName } from "../format/names";
import { InterruptedContext } from "../layout/SignedInLayout";
import { partContent, type PartState } from "../workspace/Section";
import styles from "./canvas.module.css";
import {
  FOLLOW_ZOOM,
  MAX_ZOOM,
  MIN_ZOOM,
  followTarget,
  moveDuration,
  zoomIn,
  zoomOut,
  type Move,
} from "./follow";
import { edgesOf, nodesOf, type RunEdge, type StepNode } from "./graph";
import { nodeTypes } from "./nodes";

/** The library's texts that would show on the page, set empty (CTX-D-089 (3)). */
const NO_TEXTS = {
  "node.a11yDescription.default": "",
  "node.a11yDescription.keyboardDisabled": "",
  "edge.a11yDescription.default": "",
};

/** The props of the read-only canvas (P-29 spec Section 6.6 items 7 and 9). */
export function canvasProps(
  nodes: StepNode[],
  edges: RunEdge[],
  expanded: boolean,
  onMove: ReactFlowProps["onMove"],
): ReactFlowProps<StepNode, RunEdge> {
  return {
    nodes,
    edges,
    nodeTypes,
    minZoom: MIN_ZOOM,
    maxZoom: MAX_ZOOM,
    panOnDrag: true,
    zoomOnScroll: false,
    zoomOnPinch: false,
    zoomOnDoubleClick: false,
    panOnScroll: false,
    preventScrolling: expanded,
    nodesDraggable: false,
    nodesConnectable: false,
    elementsSelectable: false,
    nodesFocusable: false,
    edgesFocusable: false,
    deleteKeyCode: null,
    disableKeyboardA11y: true,
    ariaLabelConfig: NO_TEXTS,
    proOptions: { hideAttribution: true },
    onMove,
  };
}

/** The expanded card's top: the bottom edge of the live-interrupted line when shown, else of the header. */
export function expandedTop(
  headerBottom: number,
  lineBottom: number | null,
): number {
  return lineBottom ?? headerBottom;
}

function measureTop(lineShown: boolean): number {
  const header = document.querySelector("header");
  const line = lineShown
    ? document.querySelector("main")?.previousElementSibling
    : null;
  return expandedTop(
    header?.getBoundingClientRect().bottom ?? 0,
    line ? line.getBoundingClientRect().bottom : null,
  );
}

const reducedMotion = () =>
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

export interface RunBandProps {
  part: PartState<LiveRun | null>;
  /** False in markup tests: the band without the React Flow subtree. */
  withCanvas?: boolean;
}

export function RunBand({ part, withCanvas = true }: RunBandProps) {
  if (part.failure === null && part.data) {
    return (
      <ReactFlowProvider>
        <Band run={part.data} withCanvas={withCanvas} />
      </ReactFlowProvider>
    );
  }
  return (
    <div className={styles.band}>
      <div className={styles.bar}>
        <h2 className={styles.heading}>Workflow</h2>
      </div>
      {partContent(part, () => null)}
    </div>
  );
}

export interface BandBarProps {
  run: LiveRun;
  follow: boolean;
  expanded: boolean;
  onFollow: () => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFit: () => void;
  onExpand: () => void;
}

export function BandBar(props: BandBarProps) {
  const { run } = props;
  return (
    <div className={styles.bar}>
      <h2 className={styles.heading}>Workflow</h2>
      <StageChip text={runStateName(run.state)} />
      {run.run_count > 1 && (
        <span className={styles.runCount}>
          Run {run.run_number} of {run.run_count}
        </span>
      )}
      <div className={styles.controls}>
        <button
          type="button"
          aria-pressed={props.follow}
          onClick={props.onFollow}
        >
          Follow
        </button>
        <button type="button" aria-label="Zoom in" onClick={props.onZoomIn}>
          +
        </button>
        <button type="button" aria-label="Zoom out" onClick={props.onZoomOut}>
          −
        </button>
        <button type="button" onClick={props.onFit}>
          Fit
        </button>
        <button type="button" onClick={props.onExpand}>
          {props.expanded ? "Close" : "Expand"}
        </button>
      </div>
    </div>
  );
}

interface Centred {
  target: string;
  width: number;
  height: number;
  pane: string;
}

function Band({ run, withCanvas }: { run: LiveRun; withCanvas: boolean }) {
  const flow = useReactFlow<StepNode, RunEdge>();
  const lineShown = use(InterruptedContext);
  const [follow, setFollow] = useState(true);
  const [expanded, setExpanded] = useState(false);
  const [top, setTop] = useState(0);
  const nodes = useMemo(() => nodesOf(run), [run]);
  const edges = useMemo(
    () =>
      edgesOf(run).map((edge) => ({
        ...edge,
        className: edge.data.taken ? styles.taken : styles.other,
      })),
    [run],
  );
  const target = followTarget(run);
  const node = useStore((s) => (target ? s.nodeLookup.get(target) : undefined));
  const pane = useStore((s) => `${s.width}x${s.height}`);
  const centred = useRef<Centred | null>(null);

  const centre = (move: Move) => {
    if (!node?.measured.width || !node.measured.height || target === null) {
      return;
    }
    const { width, height } = node.measured;
    centred.current = { target, width, height, pane };
    void flow.setCenter(
      node.internals.positionAbsolute.x + width / 2,
      node.internals.positionAbsolute.y + height / 2,
      { zoom: FOLLOW_ZOOM, duration: moveDuration(move, reducedMotion()) },
    );
  };

  useEffect(() => {
    if (!follow || !node?.measured.width || !node.measured.height) {
      return;
    }
    const last = centred.current;
    const { width, height } = node.measured;
    if (last === null) {
      centre("first");
    } else if (last.target !== target) {
      centre("follow");
    } else if (
      last.width !== width ||
      last.height !== height ||
      last.pane !== pane
    ) {
      centre("resize");
    }
  });

  useLayoutEffect(() => {
    if (!expanded) {
      return;
    }
    const root = document.documentElement;
    root.style.overflow = "hidden";
    const measure = () => setTop(measureTop(lineShown));
    measure();
    window.addEventListener("resize", measure);
    return () => {
      window.removeEventListener("resize", measure);
      root.style.overflow = "";
    };
  }, [expanded, lineShown]);

  const stopFollow = () => setFollow(false);
  return (
    <div
      className={expanded ? `${styles.band} ${styles.expanded}` : styles.band}
      style={expanded ? { top } : undefined}
      data-testid="run-band"
    >
      <BandBar
        run={run}
        follow={follow}
        expanded={expanded}
        onFollow={() => {
          setFollow(true);
          centre("follow");
        }}
        onZoomIn={() => {
          stopFollow();
          void flow.zoomTo(zoomIn(flow.getZoom()), {
            duration: moveDuration("zoom", reducedMotion()),
          });
        }}
        onZoomOut={() => {
          stopFollow();
          void flow.zoomTo(zoomOut(flow.getZoom()), {
            duration: moveDuration("zoom", reducedMotion()),
          });
        }}
        onFit={() => {
          stopFollow();
          void flow.fitView({
            padding: 0.1,
            minZoom: MIN_ZOOM,
            maxZoom: MAX_ZOOM,
            duration: moveDuration("fit", reducedMotion()),
          });
        }}
        onExpand={() => {
          if (!expanded) {
            window.scrollTo(0, 0);
          }
          setExpanded(!expanded);
        }}
      />
      <div className={styles.canvas}>
        {withCanvas && (
          <ReactFlow
            {...canvasProps(nodes, edges, expanded, (event) => {
              if (event) {
                stopFollow();
              }
            })}
          />
        )}
      </div>
    </div>
  );
}
