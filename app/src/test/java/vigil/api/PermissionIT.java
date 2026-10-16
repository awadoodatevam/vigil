package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.identity.api.Permission;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.ApiClient.Response;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class PermissionIT {

  private static final String FORBIDDEN = "You do not have permission to do this.";
  private static final String PROBE = "/api/v1/test-probe";

  private static VigilDatabase database;
  private static VigilTestApp app;
  private static ApiClient client;
  private static TestTenants.Tenant tenant;

  @BeforeAll
  static void start() {
    database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, List.of("--vigil.test.permission-probe=true"));
    app.awaitStarted();
    client = new ApiClient(app);
    tenant =
        new TestTenants(app)
            .tenant("Tenant A")
            .host("a.test")
            .user("viewer", "Viewer", Permission.VIEW)
            .user("acker", "Acker", Permission.ACKNOWLEDGE)
            .user("approver", "Approver", Permission.APPROVE_ACTION)
            .user("nobody", "Nobody")
            .user("revoked", "Revoked", Permission.VIEW)
            .create();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  /** A logged-in user's session cookie and CSRF cookie and header on {@code request}. */
  private static Request as(String username, Request request) {
    Response login = client.login("a.test", username, tenant.password(username));
    assertThat(login.status()).isEqualTo(200);
    String csrf = login.cookieValue("vigil_csrf");
    return request
        .host("a.test")
        .cookie("vigil_session", login.cookieValue("vigil_session"))
        .cookie("vigil_csrf", csrf)
        .header("X-Vigil-CSRF", csrf);
  }

  /**
   * Sends {@code request}, which MUST be refused with {@code status}, without calling the probe.
   */
  private static Response refused(Request request, int status) {
    int before = PermissionProbe.CALLS.get();
    Response response = request.send();
    assertThat(response.status()).as(response.text()).isEqualTo(status);
    assertThat(PermissionProbe.CALLS.get()).as("probe calls").isEqualTo(before);
    return response;
  }

  @Test
  @DisplayName("AR-I-07 the permission is checked before the operation; logged in needs none")
  void permissionCheck() {
    assertThat(as("viewer", client.get(PROBE)).send().status()).isEqualTo(200);
    assertThat(as("nobody", client.get("/api/v1/session")).send().status()).isEqualTo(200);
    assertThat(as("nobody", client.get("/api/v1/tenant")).send().status()).isEqualTo(200);
    assertThat(as("acker", client.post(PROBE)).send().status()).isEqualTo(204);
    assertThat(as("approver", client.post(PROBE)).send().status()).isEqualTo(204);
    refused(as("nobody", client.get(PROBE)), 403);
    refused(as("nobody", client.post(PROBE)), 403);
    refused(client.get(PROBE).host("a.test"), 401);
    refused(client.post(PROBE).host("a.test"), 401);
  }

  @Test
  @DisplayName(
      "PM-R-09 a refused action never reaches the operation; a revoked grant counts at once")
  void refusedActionsDoNotReachTheOperation() {
    refused(as("nobody", client.get(PROBE)), 403);
    refused(as("nobody", client.post(PROBE)), 403);
    refused(as("viewer", client.post(PROBE)), 403);
    refused(client.get(PROBE).host("a.test"), 401);
    refused(client.post(PROBE).host("a.test"), 401);
    Request revoked = as("revoked", client.get(PROBE));
    assertThat(revoked.send().status()).isEqualTo(200);
    database
        .postgres()
        .sql(
            "UPDATE identity.permission_grant SET active = false WHERE tenant_id = ?"
                + " AND permission = 'view' AND user_id = (SELECT id FROM identity.\"user\""
                + " WHERE tenant_id = ? AND username = 'revoked')")
        .param(tenant.id().value())
        .param(tenant.id().value())
        .update();
    refused(revoked, 403);
  }

  @Test
  @DisplayName("DC-U-05 a refused action answers forbidden with the exact text")
  void refusalText() {
    for (Response response :
        List.of(
            refused(as("nobody", client.get(PROBE)), 403),
            refused(as("nobody", client.post(PROBE)), 403))) {
      assertThat(response.json().get("error").get("code").stringValue()).isEqualTo("forbidden");
      assertThat(response.json().get("error").get("message").stringValue()).isEqualTo(FORBIDDEN);
    }
  }
}
