package vigil.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import vigil.setup.api.Installation;
import vigil.setup.api.PartView;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;
import vigil.tenancy.api.AliasRevision;

/**
 * The installation operations AR-I-95 to AR-I-98, which the reset of the control panel uses
 * (AR-B-03); only with the installation token (AR-S-07), on any host.
 */
@RestController
@Profile("!migrate")
class InstallationController {

  private static final Pattern UUID_FORM =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final Installation installation;
  private final SeedImports seedImports;

  InstallationController(Installation installation, SeedImports seedImports) {
    this.installation = installation;
    this.seedImports = seedImports;
  }

  /** AR-I-95: the body is read as a JSON tree; other fields are ignored. */
  @PostMapping(path = "/api/v1/installation/tenants", consumes = MediaType.APPLICATION_JSON_VALUE)
  @InstallationToken
  TenantCreated createTenant(@RequestBody JsonNode body) {
    if (!body.isObject() || body.get("name") == null || !body.get("name").isString()) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    return new TenantCreated(
        installation.createTenant(body.get("name").stringValue()).value().toString());
  }

  /** AR-I-96: a seed bundle file of at most 10 MiB in the form field {@code bundle}. */
  @PostMapping(
      path = "/api/v1/installation/tenants/{tenant_id}/seed-imports",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @InstallationToken
  ResponseEntity<SeedImportStarted> startSeedImport(
      @PathVariable("tenant_id") String tenantId,
      @RequestPart(name = "bundle", required = false) MultipartFile bundle) {
    TenantId tenant = new TenantId(uuid(tenantId));
    if (bundle == null) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    byte[] bytes;
    try {
      bytes = bundle.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    SeedImportView started = seedImports.start(tenant, bytes);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new SeedImportStarted(started.id().toString()));
  }

  /** AR-I-97. */
  @GetMapping("/api/v1/installation/seed-imports/{seed_import_id}")
  @InstallationToken
  SeedImport seedImport(@PathVariable("seed_import_id") String seedImportId) {
    return seedImports
        .seedImport(uuid(seedImportId))
        .map(SeedImport::of)
        .orElseThrow(() -> new VigilError(Code.NOT_FOUND));
  }

  /** AR-I-98: the next alias revision of the host name, to a {@code ready} tenant. */
  @PutMapping(
      path = "/api/v1/installation/aliases/{host_name}",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @InstallationToken
  Alias setAlias(@PathVariable("host_name") String hostName, @RequestBody JsonNode body) {
    JsonNode tenantId = body.isObject() ? body.get("tenant_id") : null;
    if (tenantId == null || !tenantId.isString()) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    AliasRevision revision =
        installation.setAlias(hostName, new TenantId(uuid(tenantId.stringValue())));
    return new Alias(
        revision.hostName(),
        revision.revision(),
        revision.tenantId().value().toString(),
        ApiTimes.format(revision.setAt()));
  }

  /** A UUID in the 8-4-4-4-12 hexadecimal form, any case; otherwise {@code bad_request}. */
  private static UUID uuid(String text) {
    if (text == null || !UUID_FORM.matcher(text).matches()) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    return UUID.fromString(text);
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record TenantCreated(String tenantId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record SeedImportStarted(String seedImportId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Alias(String hostName, long revision, String tenantId, String setAt) {}

  /** The seed import record of AR-I-97 (DM-E-82), without {@code started_by}. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record SeedImport(
      String seedImportId,
      String tenantId,
      String bundleName,
      String bundleVersion,
      String state,
      String startedAt,
      String finishedAt,
      List<Part> parts) {

    static SeedImport of(SeedImportView view) {
      return new SeedImport(
          view.id().toString(),
          view.tenantId().value().toString(),
          view.bundleName(),
          view.bundleVersion(),
          view.state().key(),
          ApiTimes.format(view.startedAt()),
          view.finishedAt() == null ? null : ApiTimes.format(view.finishedAt()),
          view.parts().stream().map(Part::of).toList());
    }
  }

  /** One part of a seed import record. */
  record Part(String module, String outcome, Map<String, Integer> counts, String error) {

    static Part of(PartView view) {
      return new Part(view.module(), view.outcome().key(), view.counts(), view.error());
    }
  }
}
