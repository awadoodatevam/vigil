@ApplicationModule(
    allowedDependencies = {"shared", "tenancy :: api", "identity :: api", "audit :: api"})
package vigil.incidents;

import org.springframework.modulith.ApplicationModule;
