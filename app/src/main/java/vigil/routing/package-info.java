@ApplicationModule(
    allowedDependencies = {
      "shared",
      "incidents :: api",
      "servicemap :: api",
      "identity :: api",
      "notifications :: api",
      "ticketing :: api",
      "audit :: api"
    })
package vigil.routing;

import org.springframework.modulith.ApplicationModule;
