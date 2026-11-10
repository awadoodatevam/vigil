package vigil.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.aigateway.api.AiGatewayImport;
import vigil.aigateway.api.AiGatewayPart;
import vigil.aigateway.api.AiGatewayPart.ProviderConfigurationPart;
import vigil.connectors.api.ConnectorImport;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class AiGatewayImportIT {

  private static final String AT = "ai_provider_configurations[0]";
  private static VigilTestApp app;
  private static JdbcClient jdbc;

  @BeforeAll
  static void start() {
    VigilDatabase database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, TestSecrets.create(database, Map.of()).folder(), List.of());
    app.awaitStarted();
    jdbc = app.context().getBean(JdbcClient.class);
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  /** A new tenant with part 3; returns the tenant and the handles of part 3. */
  private record Tenant(TenantId id, Map<String, UUID> handles) {}

  private static Tenant tenant() {
    TenantId tenantId = new TenantId(Ids.newId());
    Map<String, UUID> handles =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantId, AiFixture.connectors("http://127.0.0.1:1"), Instant.now(), Map.of());
    return new Tenant(tenantId, handles);
  }

  private static void importPart(Tenant tenant, AiGatewayPart part) {
    app.context()
        .getBean(AiGatewayImport.class)
        .importPart(tenant.id(), part, Instant.now(), tenant.handles());
  }

  private static Tenant imported() {
    Tenant tenant = tenant();
    importPart(tenant, AiFixture.demoPart());
    return tenant;
  }

  private static List<String> refused(AiGatewayPart part) {
    Tenant tenant = tenant();
    VigilError error = catchThrowableOfType(VigilError.class, () -> importPart(tenant, part));
    assertThat(error).isNotNull();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM ai_gateway.ai_provider_configuration"
                        + " WHERE tenant_id = :t")
                .param("t", tenant.id().value())
                .query(Long.class)
                .single())
        .isZero();
    return AiFixture.problems(error);
  }

  private static Map<String, Object> configuration(Tenant tenant, String key) {
    return jdbc.sql(
            "SELECT provider_kind, connector_instance_id, model_name, location,"
                + " price_input_per_million, price_output_per_million, active"
                + " FROM ai_gateway.ai_provider_configuration"
                + " WHERE tenant_id = :t AND provider_key = :k")
        .param("t", tenant.id().value())
        .param("k", key)
        .query()
        .singleRow();
  }

  private static void assertProvider(
      String key,
      String kind,
      String handle,
      String model,
      String location,
      String input,
      String output) {
    Tenant tenant = imported();
    Map<String, Object> row = configuration(tenant, key);
    assertThat(row.get("provider_kind")).isEqualTo(kind);
    assertThat(row.get("connector_instance_id")).isEqualTo(tenant.handles().get(handle));
    assertThat(row.get("model_name")).isEqualTo(model);
    assertThat(row.get("location")).isEqualTo(location);
    assertThat((BigDecimal) row.get("price_input_per_million")).isEqualByComparingTo(input);
    assertThat((BigDecimal) row.get("price_output_per_million")).isEqualByComparingTo(output);
    assertThat(row.get("active")).isEqualTo(true);
  }

  @Test
  @DisplayName("AR-G-01 the demo part 7 imports five configurations; claude with model and prices")
  void claude() {
    Tenant tenant = imported();
    assertThat(
            jdbc.sql(
                    "SELECT provider_key FROM ai_gateway.ai_provider_configuration"
                        + " WHERE tenant_id = :t ORDER BY provider_key")
                .param("t", tenant.id().value())
                .query(String.class)
                .list())
        .containsExactly("claude", "local-embed", "local-llm", "openai", "openai-embed");
    assertProvider(
        "claude", "claude", "claude-api", "claude-sonnet-5-5", "external", "2.00", "10.00");
  }

  @Test
  @DisplayName("AR-G-02 the seeded provider openai")
  void openai() {
    assertProvider("openai", "openai", "openai-api", "gpt-6.1-sol", "external", "2.00", "10.00");
  }

  @Test
  @DisplayName("AR-G-03 the seeded provider openai-embed")
  void openaiEmbed() {
    assertProvider(
        "openai-embed",
        "openai",
        "openai-embed-api",
        "text-embedding-3-small",
        "external",
        "0.02",
        "0");
  }

  @Test
  @DisplayName("AR-G-04 the seeded provider local-llm")
  void localLlm() {
    assertProvider("local-llm", "local_model", "local-llm", "gemma4:12b", "in_house", "0", "0");
  }

  @Test
  @DisplayName("AR-G-05 the seeded provider local-embed")
  void localEmbed() {
    assertProvider(
        "local-embed", "local_embedding", "local-embed", "embeddinggemma", "in_house", "0", "0");
  }

  @Test
  @DisplayName("PM-M18-05 the default task assignments, allowed providers and data routing")
  void defaults() {
    Tenant tenant = imported();
    Map<String, String> assignments = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT task, provider_keys::text AS keys, updated_by_kind, updated_by_module"
                + " FROM ai_gateway.task_assignment WHERE tenant_id = :t ORDER BY task")
        .param("t", tenant.id().value())
        .query()
        .listOfRows()
        .forEach(
            row -> {
              assertThat(row.get("updated_by_kind")).isEqualTo("module");
              assertThat(row.get("updated_by_module")).isEqualTo("vigil-setup");
              assignments.put((String) row.get("task"), (String) row.get("keys"));
            });
    assertThat(assignments)
        .containsExactly(
            Map.entry("SL-T-1", "[\"claude\", \"openai\", \"local-llm\"]"),
            Map.entry("SL-T-2", "[\"claude\", \"openai\", \"local-llm\"]"),
            Map.entry("SL-T-3", "[\"local-embed\"]"));
    List<String> rules =
        jdbc.sql(
                "SELECT data_category || ' ' || allowed_provider_keys::text"
                    + " FROM ai_gateway.data_routing_rule WHERE tenant_id = :t"
                    + " ORDER BY data_category")
            .param("t", tenant.id().value())
            .query(String.class)
            .list();
    String all = " [\"claude\", \"local-embed\", \"local-llm\", \"openai\", \"openai-embed\"]";
    assertThat(rules)
        .containsExactly(
            "alert_data" + all,
            "diagnostic_output" + all,
            "incident_history" + all,
            "incident_record" + all,
            "runbook_text" + all,
            "service_context" + all);
    List<String> allowed =
        jdbc.sql(
                "SELECT task || ' ' || provider_kind FROM ai_gateway.allowed_provider"
                    + " WHERE tenant_id = :t ORDER BY task, provider_kind")
            .param("t", tenant.id().value())
            .query(String.class)
            .list();
    assertThat(allowed)
        .containsExactly(
            "SL-T-1 claude",
            "SL-T-1 local_model",
            "SL-T-1 openai",
            "SL-T-2 claude",
            "SL-T-2 local_model",
            "SL-T-2 openai",
            "SL-T-3 local_embedding",
            "SL-T-3 openai");
  }

  private static AiGatewayPart claude(UnaryOperator<ProviderConfigurationPart> change) {
    return AiFixture.configuration(AiFixture.demoPart(), "claude", change);
  }

  @Test
  @DisplayName("DM-E-70 each configuration check refuses the part with its text")
  void configurationChecks() {
    assertThat(refused(claude(c -> AiFixture.withKey(c, "bad key"))))
        .contains(AT + ".provider_key: not a key");
    List<ProviderConfigurationPart> twice =
        new ArrayList<>(AiFixture.demoPart().providerConfigurations());
    twice.add(twice.getFirst());
    assertThat(refused(AiFixture.configurations(AiFixture.demoPart(), twice)))
        .containsExactly("ai_provider_configurations[5].provider_key: used more than once");
    assertThat(refused(claude(c -> AiFixture.withKind(c, "gemini"))))
        .containsExactly(
            AT + ".provider_kind: not one of claude, openai, local_model, local_embedding");
    assertThat(refused(claude(c -> AiFixture.withInstance(c, "cred-claude"))))
        .containsExactly(AT + ".connector_instance: not a connector instance of part 3");
    assertThat(refused(claude(c -> AiFixture.withInstance(c, "openai-api"))))
        .containsExactly(AT + ".connector_instance: not a claude instance");
    assertThat(refused(claude(c -> AiFixture.withLocation(c, "in_house"))))
        .containsExactly(
            AT + ".location: must be external for claude",
            AT + ".price_input_per_million: must be 0 for in_house",
            AT + ".price_output_per_million: must be 0 for in_house");
    assertThat(refused(claude(c -> AiFixture.withModel(c, ""))))
        .containsExactly(AT + ".model_name: 1 to 100 characters");
    assertThat(refused(claude(c -> AiFixture.withModel(c, "m".repeat(101)))))
        .containsExactly(AT + ".model_name: 1 to 100 characters");
    for (String price : List.of("\"2.00\"", "-1", "0.0000001", "1000000", "1e400", "abc")) {
      assertThat(refused(claude(c -> AiFixture.withPrices(c, price, "10.00"))))
          .as(price)
          .containsExactly(AT + ".price_input_per_million: not a price");
    }
    assertThat(refused(claude(c -> AiFixture.withPrices(c, "2.00", null))))
        .containsExactly(AT + ".price_output_per_million: not a price");
    ProviderConfigurationPart infinite =
        new ProviderConfigurationPart(
            "claude",
            "claude",
            "claude-api",
            "external",
            "claude-sonnet-5-5",
            AiFixture.YAML.getNodeFactory().numberNode(Double.POSITIVE_INFINITY),
            AiFixture.yaml("10.00"));
    assertThat(refused(claude(c -> infinite)))
        .containsExactly(AT + ".price_input_per_million: not a price");
    Tenant largest = tenant();
    importPart(largest, claude(c -> AiFixture.withPrices(c, "999999.999999", "2.500000")));
    assertThat((BigDecimal) configuration(largest, "claude").get("price_input_per_million"))
        .isEqualByComparingTo("999999.999999");
  }

  @Test
  @DisplayName("DM-E-71 allowed providers other than DM-E71-R1 are refused")
  void allowedProviders() {
    assertThat(
            refused(
                AiFixture.allowed(
                    AiFixture.demoPart(),
                    allowed -> {
                      allowed.put("SL-T-3", List.of("local_embedding", "openai", "openai"));
                      allowed.put("SL-T-1", List.of("claude", "openai"));
                      allowed.remove("SL-T-2");
                      allowed.put("SL-T-4", List.of("claude"));
                      return allowed;
                    })))
        .containsExactly(
            "allowed_providers.SL-T-1: must be exactly claude, openai, local_model",
            "allowed_providers.SL-T-3: must be exactly local_embedding, openai",
            "allowed_providers: unknown key SL-T-4",
            "allowed_providers: missing SL-T-2");
  }

  @Test
  @DisplayName("DM-R-57 the task assignment checks, with two providers for SL-T-3")
  void assignments() {
    assertThat(
            refused(
                AiFixture.assignments(
                    AiFixture.demoPart(),
                    assignments -> {
                      assignments.put(
                          "SL-T-1", List.of("claude", "claude", "gemini", "local-embed"));
                      assignments.put("SL-T-2", List.of());
                      assignments.put("SL-T-3", List.of("local-embed", "openai-embed"));
                      return assignments;
                    })))
        .containsExactly(
            "task_assignments.SL-T-1: duplicate claude",
            "task_assignments.SL-T-1: unknown provider gemini",
            "task_assignments.SL-T-1: local-embed is not allowed for SL-T-1",
            "task_assignments.SL-T-2: at least one provider",
            "task_assignments.SL-T-3: SL-T-3 has exactly one provider (DM-E72-R2)");
    assertThat(
            refused(
                AiFixture.assignments(
                    AiFixture.demoPart(),
                    assignments -> {
                      assignments.put("SL-T-3", List.of());
                      assignments.remove("SL-T-1");
                      return assignments;
                    })))
        .containsExactly(
            "task_assignments.SL-T-3: at least one provider", "task_assignments: missing SL-T-1");
  }

  @Test
  @DisplayName(
      "DM-E-73 the data routing checks: an empty list accepted, a seventh category refused")
  void dataRoutingRules() {
    Tenant tenant = tenant();
    importPart(
        tenant,
        AiFixture.rules(
            AiFixture.demoPart(),
            rules -> {
              rules.put("incident_record", List.of());
              rules.put("alert_data", List.of("openai", "claude"));
              return rules;
            }));
    assertThat(
            jdbc.sql(
                    "SELECT allowed_provider_keys::text FROM ai_gateway.data_routing_rule"
                        + " WHERE tenant_id = :t AND data_category = :c")
                .param("t", tenant.id().value())
                .param("c", "alert_data")
                .query(String.class)
                .single())
        .isEqualTo("[\"claude\", \"openai\"]");
    assertThat(
            refused(
                AiFixture.rules(
                    AiFixture.demoPart(),
                    rules -> {
                      rules.put("alert_data", List.of("claude", "claude", "gemini"));
                      rules.put("customer_data", List.of("claude"));
                      rules.remove("runbook_text");
                      return rules;
                    })))
        .containsExactly(
            "data_routing_rules.alert_data: duplicate claude",
            "data_routing_rules.alert_data: unknown provider gemini",
            "data_routing_rules: unknown key customer_data",
            "data_routing_rules: missing runbook_text");
  }

  @Test
  @DisplayName("DM-R-56 a second import of part 7 into the same tenant is refused")
  void secondImport() {
    Tenant tenant = imported();
    VigilError error =
        catchThrowableOfType(VigilError.class, () -> importPart(tenant, AiFixture.demoPart()));
    assertThat(AiFixture.problems(error)).containsExactly("AI gateway records already exist");
  }
}
