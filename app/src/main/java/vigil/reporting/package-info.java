@ApplicationModule(
    allowedDependencies = {
      "shared",
      "incidents :: api",
      "intake :: api",
      "knowledge :: api",
      "tenancy :: api",
      "audit :: api"
    })
package vigil.reporting;

import org.springframework.modulith.ApplicationModule;
