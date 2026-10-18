package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.MonitoringSourceConnector;
import vigil.connectors.api.ParseResult;
import vigil.connectors.api.ParsedEntry;

class MonitoringConnectorsTest {

  /** A connector of one kind that does nothing. */
  private record TestConnector(ConnectorKind kind) implements MonitoringSourceConnector {

    @Override
    public ParseResult parse(byte[] body, String contentType) {
      return new ParseResult.Failed("test");
    }

    @Override
    public ConnectionTestResult test(ConnectorInstanceView instance) {
      return new ConnectionTestResult.Passed();
    }
  }

  @Test
  @DisplayName("PM-M04-02 connectors are found by kind; a wrong or second one stops the start")
  void registry() {
    TestConnector alertmanager = new TestConnector(ConnectorKind.ALERTMANAGER);
    MonitoringConnectors registry = new MonitoringConnectors(List.of(alertmanager));
    assertThat(registry.find(ConnectorKind.ALERTMANAGER)).containsSame(alertmanager);
    assertThat(registry.find(ConnectorKind.ZABBIX)).isEmpty();
    assertThatThrownBy(
            () ->
                new MonitoringConnectors(
                    List.of(
                        new TestConnector(ConnectorKind.ZABBIX),
                        new TestConnector(ConnectorKind.ZABBIX))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("two connectors for zabbix");
    assertThatThrownBy(
            () -> new MonitoringConnectors(List.of(new TestConnector(ConnectorKind.JIRA_CLOUD))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("connector for jira_cloud is not a monitoring source kind");
    MonitoringConnectors empty = new MonitoringConnectors(List.of());
    for (ConnectorKind kind : ConnectorKind.values()) {
      assertThat(empty.find(kind)).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "PM-M04-01 the monitoring source type is an api interface the two connectors implement")
  void typeInterface() {
    assertThat(MonitoringSourceConnector.class.isInterface()).isTrue();
    assertThat(MonitoringSourceConnector.class.getPackageName()).isEqualTo("vigil.connectors.api");
    assertThat(MonitoringSourceConnector.class).isAssignableFrom(ZabbixConnector.class);
    assertThat(MonitoringSourceConnector.class).isAssignableFrom(AlertmanagerConnector.class);
  }

  @Test
  @DisplayName("AR-K-00a the monitoring source type offers exactly kind, parse and test")
  void operations() {
    List<String> methods =
        Arrays.stream(MonitoringSourceConnector.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic() && !Modifier.isStatic(method.getModifiers()))
            .map(MonitoringConnectorsTest::signature)
            .sorted()
            .toList();
    assertThat(methods)
        .containsExactly(
            "ConnectionTestResult test(ConnectorInstanceView)",
            "ConnectorKind kind()",
            "ParseResult parse(byte[],String)");
    assertThat(ParseResult.class.isSealed()).isTrue();
    assertThat(ParseResult.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(ParseResult.Parsed.class, ParseResult.Failed.class);
    assertThat(ParsedEntry.class.isSealed()).isTrue();
    assertThat(ParsedEntry.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(ParsedEntry.Normalised.class, ParsedEntry.Failed.class);
    assertThat(ConnectionTestResult.class.isSealed()).isTrue();
    assertThat(ConnectionTestResult.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            ConnectionTestResult.Passed.class, ConnectionTestResult.Failed.class);
    for (Class<?> type :
        List.of(
            ParseResult.Parsed.class,
            ParseResult.Failed.class,
            ParsedEntry.Normalised.class,
            ParsedEntry.Failed.class,
            ConnectionTestResult.Passed.class,
            ConnectionTestResult.Failed.class)) {
      assertThat(type.isRecord()).as(type.getName()).isTrue();
    }
  }

  private static String signature(Method method) {
    return method.getReturnType().getSimpleName()
        + " "
        + method.getName()
        + "("
        + String.join(
            ",", Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toList())
        + ")";
  }
}
