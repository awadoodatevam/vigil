package vigil.actions.api;

/**
 * A state check (DM-V-08): an operation of permission kind {@code diagnostic} with its parameters
 * (JSON object text), the name of a value of its output, an operator and the value compared.
 */
public record StateCheck(
    String operationKey,
    String parametersJson,
    String resultField,
    StateCheckOperator operator,
    String value) {}
