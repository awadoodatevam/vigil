@ApplicationModule(
    allowedDependencies = {"shared", "tenancy :: api", "connectors :: api", "audit :: api"})
package vigil.runner;

import org.springframework.modulith.ApplicationModule;
