package vigil.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The AR-Q-05 helper: delivers an event twice to its listener and checks that the second delivery
 * leaves the same records as the first. {@code deliverOnce} calls the listener method on the target
 * object ({@code AopTestUtils.getUltimateTargetObject(bean)}), so the call is synchronous and runs
 * in the helper's transaction, as the listener's own transaction would.
 */
public final class DoubleDelivery {

  private DoubleDelivery() {}

  public static void assertSameResult(
      JdbcClient db, String schema, TransactionTemplate tx, Runnable deliverOnce) {
    readAll(db, schema);
    tx.executeWithoutResult(status -> deliverOnce.run());
    Map<String, List<Map<String, Object>>> afterFirst = readAll(db, schema);
    tx.executeWithoutResult(status -> deliverOnce.run());
    Map<String, List<Map<String, Object>>> afterSecond = readAll(db, schema);
    assertThat(afterSecond)
        .as("rows of schema %s after the second delivery", schema)
        .isEqualTo(afterFirst);
  }

  /** Every row of every table of {@code schema}, ordered by all columns, per table. */
  private static Map<String, List<Map<String, Object>>> readAll(JdbcClient db, String schema) {
    List<String> tables =
        db.sql(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name")
            .param(schema)
            .query(String.class)
            .list();
    Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
    for (String table : tables) {
      List<String> columns =
          db.sql(
                  "SELECT column_name FROM information_schema.columns"
                      + " WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")
              .params(schema, table)
              .query(String.class)
              .list();
      String orderBy =
          columns.stream().map(c -> quote(c) + "::text").collect(Collectors.joining(", "));
      rows.put(
          table,
          new ArrayList<>(
              db.sql("SELECT * FROM " + quote(schema) + "." + quote(table) + " ORDER BY " + orderBy)
                  .query()
                  .listOfRows()));
    }
    return rows;
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
