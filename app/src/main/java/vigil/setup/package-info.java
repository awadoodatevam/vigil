@ApplicationModule(
    allowedDependencies = {
      "shared",
      "connectors :: api",
      "intake :: api",
      "servicemap :: api",
      "identity :: api",
      "audit :: api",
      "tenancy :: api",
      "runner :: api",
      "aigateway :: api",
      "knowledge :: api",
      "incidents :: api",
      "correlation :: api",
      "routing :: api",
      "diagnostics :: api",
      "recovery :: api",
      "actions :: api",
      "notifications :: api",
      "workflows :: api",
      "reporting :: api"
    })
package vigil.setup;

import org.springframework.modulith.ApplicationModule;
