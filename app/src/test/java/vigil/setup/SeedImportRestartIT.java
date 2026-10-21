package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.setup.api.Installation;
import vigil.setup.api.PartOutcome;
import vigil.setup.api.PartView;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantState;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class SeedImportRestartIT {

  @Test
  @DisplayName("AR-R-24 restart step 2 fails every running seed import and its new tenant")
  void restartFailsRunningImports() {
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = SeedTests.secrets(database);
    TenantId provisioning;
    UUID interrupted = Ids.newId();
    SeedImportView finished;
    try (VigilTestApp app = VigilTestApp.start(database, secrets.folder(), List.of())) {
      app.awaitStarted();
      Installation installation = app.context().getBean(Installation.class);
      SeedImports seedImports = app.context().getBean(SeedImports.class);
      provisioning = installation.createTenant("restart test " + Ids.newId());
      List<PartView> parts = new ArrayList<>();
      for (SeedParts.Part part : SeedParts.ALL) {
        parts.add(
            part.number() == 1
                ? new PartView(
                    part.module(), PartOutcome.SUCCEEDED, Map.of("tenant_settings", 1), null)
                : new PartView(part.module(), PartOutcome.NOT_STARTED, Map.of(), null));
      }
      Timestamp now = Timestamp.from(Instant.now());
      database
          .postgres()
          .sql(
              "INSERT INTO setup.seed_import (id, tenant_id, created_at, updated_at, bundle_name,"
                  + " bundle_version, started_by_kind, started_by_user_id, started_by_module,"
                  + " state, parts, finished_at) VALUES (:id, :tenantId, :now, :now, 'jazz-demo',"
                  + " :version, 'module', NULL, 'vigil-setup', 'running', CAST(:parts AS jsonb),"
                  + " NULL)")
          .param("id", interrupted)
          .param("tenantId", provisioning.value())
          .param("now", now)
          .param("version", SeedTests.version())
          .param("parts", SeedImportRecords.partsJson(parts))
          .update();
      TenantId ready = installation.createTenant("restart test " + Ids.newId());
      finished =
          SeedTests.await(seedImports, seedImports.start(ready, SeedTests.demoBundle()).id());
      assertThat(finished.state()).isEqualTo(SeedImportState.SUCCEEDED);
      database
          .postgres()
          .sql("UPDATE setup.seed_import SET state = 'running', finished_at = NULL WHERE id = :id")
          .param("id", finished.id())
          .update();
    }
    try (VigilTestApp app = VigilTestApp.start(database, secrets.folder(), List.of())) {
      app.awaitStarted();
      SeedImports seedImports = app.context().getBean(SeedImports.class);
      TenantService tenants = app.context().getBean(TenantService.class);

      SeedImportView first = seedImports.seedImport(interrupted).orElseThrow();
      assertThat(first.state()).isEqualTo(SeedImportState.FAILED);
      assertThat(first.finishedAt()).isNotNull();
      assertThat(first.parts().get(0).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
      assertThat(first.parts().get(1).outcome()).isEqualTo(PartOutcome.FAILED);
      assertThat(first.parts().get(1).error()).isEqualTo("interrupted by restart");
      assertThat(first.parts().subList(2, 17))
          .allSatisfy(part -> assertThat(part.outcome()).isEqualTo(PartOutcome.NOT_STARTED));
      assertThat(tenants.tenant(provisioning).orElseThrow().state()).isEqualTo(TenantState.FAILED);

      SeedImportView second = seedImports.seedImport(finished.id()).orElseThrow();
      assertThat(second.state()).isEqualTo(SeedImportState.FAILED);
      assertThat(second.finishedAt()).isNotNull();
      assertThat(second.parts().get(2).outcome()).isEqualTo(PartOutcome.FAILED);
      assertThat(second.parts().get(2).error()).isEqualTo("interrupted by restart");
      assertThat(second.parts().get(0).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
      assertThat(second.parts().get(1).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
      assertThat(tenants.tenant(finished.tenantId()).orElseThrow().state())
          .isEqualTo(TenantState.READY);
    }
  }
}
