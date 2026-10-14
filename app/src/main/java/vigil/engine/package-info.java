@ApplicationModule(
    allowedDependencies = {
      "shared",
      "tenancy :: api",
      "intake :: api",
      "incidents :: api",
      "diagnostics :: api",
      "investigation :: api",
      "actions :: api",
      "recovery :: api",
      "routing :: api",
      "notifications :: api",
      "ticketing :: api",
      "knowledge :: api",
      "servicemap :: api",
      "identity :: api",
      "audit :: api"
    })
package vigil.engine;

import org.springframework.modulith.ApplicationModule;
