@ApplicationModule(
    allowedDependencies = {
      "shared",
      "runner :: api",
      "servicemap :: api",
      "incidents :: api",
      "audit :: api"
    })
package vigil.diagnostics;

import org.springframework.modulith.ApplicationModule;
