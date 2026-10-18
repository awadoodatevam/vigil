package vigil.connectors.api;

/** The result of a connection test. */
public sealed interface ConnectionTestResult
    permits ConnectionTestResult.Passed, ConnectionTestResult.Failed {

  /** The test passed. */
  record Passed() implements ConnectionTestResult {}

  /** The test did not pass, with a reason of {@code 50-architecture} Section 12.1. */
  record Failed(String reason) implements ConnectionTestResult {}
}
