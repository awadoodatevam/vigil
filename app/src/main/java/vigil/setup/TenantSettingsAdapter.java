package vigil.setup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantImport;
import vigil.tenancy.api.TenantLogo;
import vigil.tenancy.api.TenantSettingsPart;

/** Seed part 1, tenant settings, imported through {@link TenantImport} (DM-R-56 (1)). */
@Component
class TenantSettingsAdapter implements SeedPartImporter {

  private static final String SECTION = "tenant_settings";
  private static final List<String> FIELDS =
      List.of("customer_name", "customer_logo", "environment_label", "product_display_name");
  private static final Map<String, String> LOGO_MEDIA_TYPES =
      Map.of(SeedParts.LOGO_PNG, "image/png", SeedParts.LOGO_SVG, "image/svg+xml");

  private final TenantImport tenantImport;

  TenantSettingsAdapter(TenantImport tenantImport) {
    this.tenantImport = tenantImport;
  }

  @Override
  public int part() {
    return 1;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    Row settings = shapes.mapping(files.get(SeedParts.TENANT_IDENTITY), SECTION, FIELDS);
    TenantLogo logo = null;
    if (settings != null && settings.get("customer_logo") != null) {
      String name = settings.get("customer_logo");
      if (LOGO_MEDIA_TYPES.containsKey(name) && bundle.entries().containsKey(name)) {
        logo = new TenantLogo(bundle.entries().get(name), LOGO_MEDIA_TYPES.get(name));
      } else {
        shapes.add(SECTION + ".customer_logo: no entry " + name);
      }
    }
    shapes.refuseIfAny(SeedParts.part(part()).name());
    tenantImport.importPart(
        tenantId,
        new TenantSettingsPart(
            settings.get("customer_name"),
            settings.get("environment_label"),
            settings.get("product_display_name"),
            logo),
        importStartedAt,
        handles);
    return new PartResult(Map.of(SECTION, 1), Map.of());
  }
}
