package vigil.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Takes the session advisory lock that allows one vigil-app per database and holds it on its own
 * connection, outside the pool, until shutdown (AR-R-01).
 */
@Component
@Profile("!migrate")
class AdvisoryLock implements InitializingBean, DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(AdvisoryLock.class);
  private static final long LOCK_KEY = 4835711L;
  static final String HELD_MESSAGE = "Another vigil-app is running against this database.";

  private final Environment environment;
  private Connection connection;

  AdvisoryLock(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void afterPropertiesSet() throws SQLException {
    connection =
        DriverManager.getConnection(
            environment.getRequiredProperty("spring.datasource.url"),
            environment.getRequiredProperty("spring.datasource.username"),
            environment.getRequiredProperty("spring.datasource.password"));
    boolean locked;
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT pg_try_advisory_lock(" + LOCK_KEY + ")")) {
      result.next();
      locked = result.getBoolean(1);
    }
    if (!locked) {
      connection.close();
      LOG.error(HELD_MESSAGE);
      throw new IllegalStateException(HELD_MESSAGE);
    }
  }

  @Override
  public void destroy() throws SQLException {
    if (connection != null) {
      connection.close();
    }
  }
}
