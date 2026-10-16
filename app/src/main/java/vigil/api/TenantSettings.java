package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import vigil.tenancy.api.TenantSettingsView;

/** The tenant settings of AR-I-10 and AR-I-15. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record TenantSettings(
    String productDisplayName, String customerName, boolean hasLogo, String environmentLabel) {

  static TenantSettings of(TenantSettingsView view) {
    return new TenantSettings(
        view.productDisplayName(), view.customerName(), view.hasLogo(), view.environmentLabel());
  }
}
