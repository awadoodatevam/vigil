package vigil.shared;

/** The answer of a step handler: {@link Completed} or {@link Waiting} (AR-R-21). */
public sealed interface StepAnswer permits Completed, Waiting {}
