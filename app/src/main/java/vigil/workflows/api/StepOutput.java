package vigil.workflows.api;

/** A named output of a step type: its key in workflow content and its display text. */
public record StepOutput(String key, String displayText) {}
