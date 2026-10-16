package vigil.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import vigil.identity.api.Permission;

/** An operation that needs any one of the listed permissions (AR-I-07, DM-E06-R1). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface RequiresPermission {

  Permission[] value();
}
