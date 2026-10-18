package vigil.connectors.api;

/**
 * The monitoring source connector type (AR-K-00a, PM-M04-01), implemented by one bean per kind in
 * vigil-connectors.
 */
public interface MonitoringSourceConnector {

  /** The kind this connector implements. */
  ConnectorKind kind();

  /**
   * Turns a received webhook body into its entries. Makes no network call: not an external call
   * (AR-R-09).
   */
  ParseResult parse(byte[] body, String contentType);

  /**
   * Tests the connection to the instance's source.
   *
   * <p>external call: not inside a transaction
   */
  ConnectionTestResult test(ConnectorInstanceView instance);
}
