@ApplicationModule(
    allowedDependencies = {
      "shared",
      "tenancy :: api",
      "engine :: api",
      "incidents :: api",
      "routing :: api",
      "identity :: api",
      "diagnostics :: api",
      "audit :: api"
    })
package vigil.workflows;

import org.springframework.modulith.ApplicationModule;
