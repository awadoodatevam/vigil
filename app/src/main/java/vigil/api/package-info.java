@ApplicationModule(
    allowedDependencies = {
      "shared",
      "audit :: api",
      "tenancy :: api",
      "identity :: api",
      "connectors :: api",
      "runner :: api",
      "servicemap :: api",
      "intake :: api",
      "aigateway :: api",
      "knowledge :: api",
      "incidents :: api",
      "investigation :: api",
      "notifications :: api",
      "ticketing :: api",
      "routing :: api",
      "correlation :: api",
      "recovery :: api",
      "diagnostics :: api",
      "actions :: api",
      "engine :: api",
      "workflows :: api",
      "reporting :: api",
      "setup :: api"
    })
package vigil.api;

import org.springframework.modulith.ApplicationModule;
