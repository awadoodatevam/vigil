package vigil.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A monitoring webhook (AR-I-90): no session; the source's own bearer token is checked by
 * vigil-intake (AR-S-05), so the access interceptor makes no check of its own.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface WebhookToken {}
