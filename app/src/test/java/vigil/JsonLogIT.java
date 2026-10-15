package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.testsupport.VigilDatabase;

class JsonLogIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> LINES = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void runTheJar() throws Exception {
    VigilDatabase database = VigilDatabase.fresh();
    ProcessBuilder builder =
        new ProcessBuilder(VigilDatabase.javaExecutable(), "-jar", "target/vigil-app.jar")
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("SERVER_PORT", "0");
    builder.environment().put("VIGIL_SECRETS_DIR", database.appSecretsFolder().toString());
    builder.environment().put("VIGIL_DB_HOST", database.host());
    builder.environment().put("VIGIL_DB_PORT", Integer.toString(database.port()));
    builder.environment().put("VIGIL_DB_NAME", database.name());
    Process process = builder.start();
    CompletableFuture<Void> ready = new CompletableFuture<>();
    Thread reader =
        Thread.ofPlatform()
            .start(
                () -> {
                  try (BufferedReader out =
                      new BufferedReader(
                          new InputStreamReader(
                              process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                      LINES.add(line);
                      if (line.contains("\"message\":\"restart step 7 done\"")) {
                        ready.complete(null);
                      }
                    }
                  } catch (Exception e) {
                    ready.completeExceptionally(e);
                  }
                  ready.complete(null);
                });
    try {
      ready.get(120, TimeUnit.SECONDS);
    } finally {
      process.destroy();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
      reader.join(TimeUnit.SECONDS.toMillis(30));
    }
  }

  @Test
  @DisplayName("AR-S-11 every line on standard output is an ECS JSON object")
  void everyLineIsJson() {
    assertThat(LINES).isNotEmpty();
    for (String line : LINES) {
      JsonNode node = JSON.readTree(line);
      assertThat(node.isObject()).as("JSON object: %s", line).isTrue();
      assertThat(node.has("@timestamp")).as("@timestamp in %s", line).isTrue();
      assertThat(node.has("message")).as("message in %s", line).isTrue();
      assertThat(node.path("log").path("level").isString()).as("log.level in %s", line).isTrue();
    }
  }

  @Test
  @DisplayName("BP-R-16 vigil-app logs through SLF4J in JSON at level INFO")
  void restartStepSevenIsLoggedAtInfo() {
    assertThat(LINES)
        .map(JSON::readTree)
        .anySatisfy(
            node -> {
              assertThat(node.path("message").asString()).isEqualTo("restart step 7 done");
              assertThat(node.path("log").path("level").asString()).isEqualTo("INFO");
            });
  }
}
