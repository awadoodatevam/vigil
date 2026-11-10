package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.Secrets;
import vigil.connectors.api.TargetResult;
import vigil.shared.Ids;
import vigil.shared.TenantId;

class LinuxServerConnectorTest {

  /** Fails the test when the connector reads a secret. */
  private static final class NoSecrets implements Secrets {

    @Override
    public boolean verify(TenantId tenantId, UUID credentialRefId, String presented) {
      throw new AssertionError("verify called");
    }

    @Override
    public String read(TenantId tenantId, UUID credentialRefId) {
      throw new AssertionError("read called");
    }

    @Override
    public String readSeedPassword(String username) {
      throw new AssertionError("readSeedPassword called");
    }

    @Override
    public boolean verifyInstallationToken(String presented) {
      throw new AssertionError("verifyInstallationToken called");
    }
  }

  @Test
  @DisplayName("AR-K-11 after stop no SSH client is started and run returns not started")
  void afterStop() throws Exception {
    LinuxServerConnector connector = new LinuxServerConnector(new NoSecrets());
    connector.stop();
    TenantId tenant = new TenantId(Ids.newId());
    ConnectorInstanceView instance =
        new ConnectorInstanceView(
            tenant,
            Ids.newId(),
            "lab",
            ConnectorKind.LINUX_SERVER,
            "lab-db-01",
            RunnerFixture.JSON.readTree("{\"port\":22,\"host_key_sha256\":\"SHA256:x\"}"),
            null,
            true);
    assertThat(connector.run(tenant, instance, Ids.newId(), "true", Duration.ofSeconds(5)))
        .isEqualTo(new TargetResult.NotStarted("the application is closing"));
    Field client = LinuxServerConnector.class.getDeclaredField("client");
    client.setAccessible(true);
    assertThat(client.get(connector)).isNull();
  }

  @Test
  @DisplayName("AR-K-11 remaining waits are rounded up to whole milliseconds, at least 1 ms")
  void waitMillis() {
    assertThat(LinuxServerConnector.waitMillis(400_000)).isEqualTo(1);
    assertThat(LinuxServerConnector.waitMillis(1_200_000)).isEqualTo(2);
    assertThat(LinuxServerConnector.waitMillis(2_000_000)).isEqualTo(2);
    assertThat(LinuxServerConnector.waitMillis(0)).isEqualTo(1);
    assertThat(LinuxServerConnector.waitMillis(-5_000_000)).isEqualTo(1);
  }
}
