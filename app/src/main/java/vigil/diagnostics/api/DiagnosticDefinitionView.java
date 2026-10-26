package vigil.diagnostics.api;

/** A diagnostic definition (DM-E-53); {@code parameterValuesJson} is the stored JSON object. */
public record DiagnosticDefinitionView(
    String diagnosticKey,
    String name,
    String operationKey,
    String parameterValuesJson,
    String summaryTemplate,
    boolean active) {}
