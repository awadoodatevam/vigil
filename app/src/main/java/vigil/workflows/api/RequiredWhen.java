package vigil.workflows.api;

/**
 * A parameter is required when the parameter {@code parameter} has the value {@code value}, or, for
 * an {@code ENUM_SET} parameter, contains it.
 */
public record RequiredWhen(String parameter, String value) {}
