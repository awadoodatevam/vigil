package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class ConnectorInstancesIT {

  private static final String SETTINGS =
      "{\"project_key\":\"NOC\",\"issue_type\":\"Task\",\"resolved_status\":\"Done\","
          + "\"reopened_status\":\"To Do\",\"label_prefix\":\"vigil-\"}";
  private static VigilTestApp app;

  @BeforeAll
  static void start() {
    app = VigilTestApp.start(VigilDatabase.fresh());
    app.awaitStarted();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static Map<String, UUID> importPart(TenantId tenantId, List<String> handles) {
    return app.context()
        .getBean(ConnectorImport.class)
        .importPart(
            tenantId,
            new ConnectorsPart(
                List.of(TestParts.credential("cred-jira", "external_api", "secret:jira")),
                handles.stream()
                    .map(
                        handle ->
                            TestParts.connector(
                                handle, "jira_cloud", "http://jira.test", SETTINGS, "cred-jira"))
                    .toList()),
            Instant.now(),
            Map.of());
  }

  private static void set(UUID id, String column, Object value) {
    app.database()
        .postgres()
        .sql("UPDATE connectors.connector_instance SET " + column + " = :v WHERE id = :id")
        .param("v", value)
        .param("id", id)
        .update();
  }

  @Test
  @DisplayName("AR-K-00c active instances of a kind, by name in code point order, then id")
  void activeInstances() {
    TenantId tenantA = new TenantId(Ids.newId());
    TenantId tenantB = new TenantId(Ids.newId());
    Map<String, UUID> a = importPart(tenantA, List.of("jira-b", "jira-c", "jira-d", "jira-e"));
    importPart(tenantB, List.of("jira-other"));
    set(a.get("jira-b"), "name", "b jira");
    set(a.get("jira-c"), "name", "B jira");
    set(a.get("jira-d"), "name", "b jira");
    set(a.get("jira-e"), "active", false);
    ConnectorService connectors = app.context().getBean(ConnectorService.class);
    List<ConnectorInstanceView> found =
        connectors.activeInstances(tenantA, ConnectorKind.JIRA_CLOUD);
    UUID first = a.get("jira-b").compareTo(a.get("jira-d")) < 0 ? a.get("jira-b") : a.get("jira-d");
    UUID second = first.equals(a.get("jira-b")) ? a.get("jira-d") : a.get("jira-b");
    assertThat(found.stream().map(ConnectorInstanceView::id))
        .containsExactly(a.get("jira-c"), first, second);
    assertThat(found).allMatch(ConnectorInstanceView::active);
    assertThat(connectors.activeInstances(tenantA, ConnectorKind.CLAUDE)).isEmpty();
    assertThat(connectors.activeInstances(tenantB, ConnectorKind.JIRA_CLOUD))
        .extracting(ConnectorInstanceView::name)
        .containsExactly("jira-other");
  }
}
