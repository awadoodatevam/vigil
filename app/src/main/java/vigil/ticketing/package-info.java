@ApplicationModule(
    allowedDependencies = {
      "shared",
      "connectors :: api",
      "intake :: api",
      "incidents :: api",
      "investigation :: api",
      "tenancy :: api",
      "audit :: api"
    })
package vigil.ticketing;

import org.springframework.modulith.ApplicationModule;
