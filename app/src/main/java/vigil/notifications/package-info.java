@ApplicationModule(
    allowedDependencies = {
      "shared",
      "connectors :: api",
      "identity :: api",
      "incidents :: api",
      "investigation :: api",
      "tenancy :: api",
      "audit :: api"
    })
package vigil.notifications;

import org.springframework.modulith.ApplicationModule;
