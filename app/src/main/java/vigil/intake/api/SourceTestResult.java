package vigil.intake.api;

/**
 * The result of a connection test on request (PM-M06-07): {@code reason} is {@code null} when it
 * passed; {@code health} is the source's health after the result was recorded.
 */
public record SourceTestResult(TestOutcome outcome, String reason, Health health) {}
