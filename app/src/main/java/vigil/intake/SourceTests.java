package vigil.intake;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorService;
import vigil.intake.api.Health;
import vigil.intake.api.SourceTestResult;
import vigil.intake.api.SourceView;
import vigil.intake.api.TestOutcome;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;

/**
 * The connection test of a source (DM-R-39, PM-M06-07): the test with no transaction open
 * (AR-R-09), then its result recorded and the health evaluated in one transaction.
 */
@Component
class SourceTests {

  private static final Logger LOG = LoggerFactory.getLogger(SourceTests.class);
  private static final int REASON_MAX = 500;

  private final ConnectorService connectors;
  private final IntakeRecords records;
  private final SourceHealth health;
  private final TransactionTemplate tx;
  private final Clock clock;

  SourceTests(
      ConnectorService connectors,
      IntakeRecords records,
      SourceHealth health,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.connectors = connectors;
    this.records = records;
    this.health = health;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * Tests the source and records the result; an exception of the test propagates and nothing is
   * recorded.
   *
   * <p>external call: not inside a transaction
   */
  SourceTestResult test(TenantId tenantId, SourceView source) {
    ConnectionTestResult result = connectors.testConnection(tenantId, source.connectorInstanceId());
    TestOutcome outcome =
        result instanceof ConnectionTestResult.Passed ? TestOutcome.PASSED : TestOutcome.FAILED;
    String reason =
        result instanceof ConnectionTestResult.Failed failed
            ? Texts.cut(failed.reason(), REASON_MAX)
            : null;
    Health after =
        tx.execute(
            status -> {
              records.lockSource(tenantId, source.id());
              Instant now = Timestamps.now(clock);
              records.updateTest(tenantId, source.id(), outcome, reason, now);
              return health.evaluate(tenantId, source.id(), now);
            });
    return new SourceTestResult(outcome, reason, after);
  }

  /** The test of the scheduled work and the first tests: an exception is logged, not recorded. */
  void testLogged(TenantId tenantId, SourceView source) {
    try {
      test(tenantId, source);
    } catch (RuntimeException e) {
      LOG.warn(
          "connection test failed to run: tenant {} source {} ({})",
          tenantId.value(),
          source.sourceKey(),
          e.getClass().getName());
    }
  }
}
