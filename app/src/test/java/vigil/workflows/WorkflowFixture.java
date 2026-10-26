package vigil.workflows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.engine.EngineFixture;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunView;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilTestApp;
import vigil.workflows.api.WorkflowImport;
import vigil.workflows.api.WorkflowPart;

/**
 * The workflow fixture of P-41 spec Section 7: an engine fixture whose tenant has, when asked, seed
 * part 16 of the demo data imported, and a wait for the live run of an incident's episode.
 */
public final class WorkflowFixture {

  private static final Path WORKFLOW = Path.of("../docs/31-demo-data/06-workflow.yaml");

  public final EngineFixture engineFixture;
  public final TenantId tenant;

  private WorkflowFixture(EngineFixture engineFixture) {
    this.engineFixture = engineFixture;
    this.tenant = engineFixture.tenant;
  }

  public static WorkflowFixture create(
      VigilTestApp app,
      TestSecrets secrets,
      String name,
      String host,
      String address,
      String suffix,
      boolean withWorkflow) {
    EngineFixture engineFixture = EngineFixture.create(app, secrets, name, host, address, suffix);
    if (withWorkflow) {
      app.context()
          .getBean(WorkflowImport.class)
          .importPart(engineFixture.tenant, demoPart(), Instant.now(), Map.of());
    }
    return new WorkflowFixture(engineFixture);
  }

  /** The section {@code workflow} of {@code 06-workflow.yaml} as JSON text. */
  public static WorkflowPart demoPart() {
    try {
      return new WorkflowPart(
          JsonMapper.builder()
              .build()
              .writeValueAsString(
                  YAMLMapper.builder()
                      .build()
                      .readTree(Files.readString(WORKFLOW, StandardCharsets.UTF_8))
                      .get("workflow")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Waits until the incident's episode has a live run; returns its id. */
  public UUID awaitRun(UUID incident, long episode) {
    EngineFixture.await(() -> run(incident, episode) != null);
    return run(incident, episode);
  }

  private UUID run(UUID incident, long episode) {
    return engineFixture.engine.runs(tenant, incident).stream()
        .filter(run -> run.mode() == RunMode.LIVE && run.episode() == episode)
        .map(RunView::id)
        .findFirst()
        .orElse(null);
  }
}
