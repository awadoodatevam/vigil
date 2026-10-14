@ApplicationModule(
    allowedDependencies = {
      "shared",
      "incidents :: api",
      "servicemap :: api",
      "knowledge :: api",
      "aigateway :: api",
      "audit :: api"
    })
package vigil.investigation;

import org.springframework.modulith.ApplicationModule;
