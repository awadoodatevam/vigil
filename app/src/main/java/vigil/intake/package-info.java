@ApplicationModule(
    allowedDependencies = {"shared", "tenancy :: api", "connectors :: api", "audit :: api"})
package vigil.intake;

import org.springframework.modulith.ApplicationModule;
