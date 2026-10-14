@ApplicationModule(
    allowedDependencies = {"shared", "tenancy :: api", "aigateway :: api", "audit :: api"})
package vigil.knowledge;

import org.springframework.modulith.ApplicationModule;
