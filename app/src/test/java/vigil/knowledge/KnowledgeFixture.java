package vigil.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.aigateway.AiFixture;
import vigil.aigateway.api.AiGatewayImport;
import vigil.aigateway.api.AiGatewayPart;
import vigil.connectors.api.ConnectorImport;
import vigil.knowledge.api.KnowledgeImport;
import vigil.knowledge.api.KnowledgePart;
import vigil.knowledge.api.KnowledgeScheduling;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.testsupport.TestEmbedding;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilTestApp;

/**
 * The knowledge fixture of P-20 spec Section 7: a tenant with part 3 (the five AI instances), the
 * demo service map, part 7 and part 8; helpers that read the settings and embeddings and change the
 * data routing rules.
 */
public final class KnowledgeFixture {

  private static final Path SERVICE_MAP = Path.of("../docs/31-demo-data/02-service-map.yaml");

  public final VigilTestApp app;
  public final TenantId tenant;

  /** The created tenant with its users' passwords. */
  public final TestTenants.Tenant identity;

  private KnowledgeFixture(VigilTestApp app, TestTenants.Tenant identity) {
    this.app = app;
    this.tenant = identity.id();
    this.identity = identity;
  }

  /**
   * An active tenant with a host ({@code host} {@code null}: a ready tenant without alias, not
   * active); the two embedding instances at {@code TestEmbedding} and every other AI instance at a
   * closed port unless {@code addresses} names it; no part 8 when {@code part8} is {@code null}.
   */
  public static KnowledgeFixture create(
      VigilTestApp app,
      String name,
      String host,
      Map<String, String> addresses,
      UnaryOperator<AiGatewayPart> part7,
      KnowledgePart part8) {
    return create(app, name, host, addresses, part7, part8, UnaryOperator.identity());
  }

  /**
   * As {@link #create(VigilTestApp, String, String, Map, UnaryOperator, KnowledgePart)}, with the
   * tenant's settings and identity added by {@code tenant} before it is created.
   */
  public static KnowledgeFixture create(
      VigilTestApp app,
      String name,
      String host,
      Map<String, String> addresses,
      UnaryOperator<AiGatewayPart> part7,
      KnowledgePart part8,
      UnaryOperator<TestTenants.Builder> identity) {
    TestTenants.Builder builder = identity.apply(new TestTenants(app).tenant(name));
    if (host != null) {
      builder.host(host);
    }
    TestTenants.Tenant created = builder.create();
    TenantId tenant = created.id();
    Instant now = Instant.now();
    String closed = closedAddress();
    Map<String, String> all = new LinkedHashMap<>();
    for (String handle :
        List.of("claude-api", "openai-api", "openai-embed-api", "local-llm", "local-embed")) {
      String fallback =
          handle.equals("local-embed") || handle.equals("openai-embed-api")
              ? TestEmbedding.shared().address()
              : closed;
      all.put(handle, addresses.getOrDefault(handle, fallback));
    }
    Map<String, UUID> handles =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(tenant, AiFixture.connectors(all), now, Map.of());
    try {
      app.context()
          .getBean(ServiceMapService.class)
          .importFile(
              tenant,
              "02-service-map.yaml",
              Files.readAllBytes(SERVICE_MAP),
              Actor.module("vigil-setup"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    app.context()
        .getBean(AiGatewayImport.class)
        .importPart(tenant, part7.apply(AiFixture.demoPart()), now, handles);
    if (part8 != null) {
      app.context().getBean(KnowledgeImport.class).importPart(tenant, part8, now, Map.of());
      if (host != null) {
        app.context().getBean(KnowledgeScheduling.class).matchActiveTenants();
      }
    }
    return new KnowledgeFixture(app, created);
  }

  /** An address on a port the fixture bound and released. */
  public static String closedAddress() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return "http://127.0.0.1:" + socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public KnowledgeSettingsView settings() {
    return app.context().getBean(KnowledgeService.class).settings(tenant).orElseThrow();
  }

  /** The past-incident embeddings of the provider by subject key, read as {@code postgres}. */
  public List<Map<String, Object>> embeddings(String providerKey) {
    return postgres()
        .sql(
            "SELECT subject_kind, subject_key, provider_key, model_name, search_text,"
                + " vector::text AS vector FROM knowledge.incident_embedding WHERE tenant_id = :t"
                + " AND provider_key = :p ORDER BY subject_kind, subject_key COLLATE \"C\"")
        .param("t", tenant.value())
        .param("p", providerKey)
        .query()
        .listOfRows();
  }

  /** Writes the data routing rule of {@code category} as {@code postgres}. */
  public void setRouting(String category, List<String> providerKeys) {
    postgres()
        .sql(
            "UPDATE ai_gateway.data_routing_rule SET allowed_provider_keys = CAST(:keys AS jsonb)"
                + " WHERE tenant_id = :t AND data_category = :c")
        .param("keys", KnowledgeRecords.json(providerKeys))
        .param("t", tenant.value())
        .param("c", category)
        .update();
  }

  public JdbcClient postgres() {
    return app.database().postgres();
  }
}
