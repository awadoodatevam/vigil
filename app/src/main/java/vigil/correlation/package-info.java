@ApplicationModule(
    allowedDependencies = {
      "shared",
      "tenancy :: api",
      "intake :: api",
      "servicemap :: api",
      "incidents :: api",
      "routing :: api",
      "audit :: api"
    })
package vigil.correlation;

import org.springframework.modulith.ApplicationModule;
