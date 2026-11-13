// DC-S03-03, DC-S03-04 Hypothesis and customer impact (DC-TX-02; P-29 spec Section 6.5 item 2), from AR-I-22.
import type { IncidentHeader } from "../api/types";
import { componentKindName, criticalityName, listText } from "../format/names";
import { KindTag } from "../tags/KindTag";
import { Line } from "./lines";
import styles from "./workspace.module.css";

/** The hypothesis sentence of DC-TX-02: m other affected components, n of them depend on the hypothesis component. */
export function hypothesisSentence(m: number, n: number): string {
  if (m === 0) {
    return "It is the only affected component.";
  }
  if (n === m) {
    return m === 1
      ? "The other affected component depends on it."
      : `The other ${m} affected components all depend on it.`;
  }
  return `${n} of the other ${m} affected components depend on it.`;
}

const TIE_BREAKS: Record<string, string> = {
  criticality: "Tie broken by higher criticality.",
  component_key: "Tie broken by component ID.",
};

export function HypothesisContent({ incident }: { incident: IncidentHeader }) {
  const { hypothesis, affected_services: services } = incident;
  return (
    <>
      {hypothesis === null ? (
        <Line>No hypothesis: no alert matched a known component.</Line>
      ) : (
        <div className={styles.block}>
          <p className={styles.tagged}>
            <KindTag word="Hypothesis" />
            <span className={styles.strong}>
              {hypothesis.name} ({hypothesis.component_key})
            </span>
          </p>
          <Line>
            {componentKindName(hypothesis.kind)}, criticality{" "}
            {criticalityName(hypothesis.criticality)}
          </Line>
          <Line>
            {hypothesisSentence(
              hypothesis.other_affected_count,
              hypothesis.dependent_count,
            )}
          </Line>
          <Line secondary>
            Chosen by the service map rule: the affected component that most
            other affected components depend on.
          </Line>
          {TIE_BREAKS[hypothesis.tie_break] && (
            <Line secondary>{TIE_BREAKS[hypothesis.tie_break]}</Line>
          )}
        </div>
      )}
      {services.length === 0 ? (
        <Line lead="Customer impact:">none identified.</Line>
      ) : (
        <div className={styles.block}>
          <Line lead="Customer impact:" />
          {services.map((service) => (
            <Line key={service.service_key}>
              {service.name} — criticality{" "}
              {criticalityName(service.criticality)} — via{" "}
              {listText(service.via.map((component) => component.name))}
            </Line>
          ))}
        </div>
      )}
    </>
  );
}
