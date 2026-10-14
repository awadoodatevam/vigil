@ApplicationModule(
    allowedDependencies = {
      "shared",
      "intake :: api",
      "incidents :: api",
      "correlation :: api",
      "servicemap :: api",
      "audit :: api"
    })
package vigil.recovery;

import org.springframework.modulith.ApplicationModule;
