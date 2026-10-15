package vigil;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.shared.Ids;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.VigilDatabase;

class DoubleDeliveryIT {

  private static JdbcClient db;
  private static TransactionTemplate tx;

  /** A test listener that records each event once. */
  static final class IdempotentListener {
    void on(UUID eventId) {
      db.sql("INSERT INTO test_support.delivery (event_id) VALUES (?) ON CONFLICT DO NOTHING")
          .param(eventId)
          .update();
    }
  }

  /** A test listener that records every delivery. */
  static final class CountingListener {
    void on(UUID eventId) {
      db.sql("INSERT INTO test_support.delivery (event_id) VALUES (?)").param(Ids.newId()).update();
    }
  }

  @BeforeAll
  static void createSchema() {
    VigilDatabase database = VigilDatabase.fresh();
    JdbcClient postgres = database.postgres();
    postgres.sql("CREATE SCHEMA test_support").update();
    postgres.sql("CREATE TABLE test_support.delivery (event_id uuid PRIMARY KEY)").update();
    postgres.sql("GRANT USAGE ON SCHEMA test_support TO vigil_app").update();
    postgres.sql("GRANT SELECT, INSERT, UPDATE ON test_support.delivery TO vigil_app").update();
    DataSource app = database.appDataSource();
    db = JdbcClient.create(app);
    tx = new TransactionTemplate(new DataSourceTransactionManager(app));
  }

  @Test
  @DisplayName("AR-Q-05 the double-delivery helper passes for an idempotent listener")
  void passesForIdempotentListener() {
    IdempotentListener listener = AopTestUtils.getUltimateTargetObject(new IdempotentListener());
    UUID eventId = Ids.newId();
    assertThatCode(
            () ->
                DoubleDelivery.assertSameResult(db, "test_support", tx, () -> listener.on(eventId)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("AR-Q-05 the double-delivery helper fails for a listener that is not idempotent")
  void failsForNonIdempotentListener() {
    CountingListener listener = AopTestUtils.getUltimateTargetObject(new CountingListener());
    UUID eventId = Ids.newId();
    assertThatThrownBy(
            () ->
                DoubleDelivery.assertSameResult(db, "test_support", tx, () -> listener.on(eventId)))
        .isInstanceOf(AssertionError.class);
  }
}
