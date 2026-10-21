package vigil.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An installation operation: only {@code Authorization: Bearer {installation token}}, on any host,
 * without a session (AR-S-07, PM-M23-06).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface InstallationToken {}
