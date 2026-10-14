@ApplicationModule(
    allowedDependencies = {
      "shared",
      "knowledge :: api",
      "runner :: api",
      "servicemap :: api",
      "incidents :: api",
      "identity :: api",
      "notifications :: api",
      "recovery :: api",
      "audit :: api"
    })
package vigil.actions;

import org.springframework.modulith.ApplicationModule;
