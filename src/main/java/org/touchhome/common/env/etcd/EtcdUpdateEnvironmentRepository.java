package org.touchhome.common.env.etcd;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Annotation that configure storing environemnt into mongo and able to update on fly
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
@Documented
@Import({ EtcdUpdateEnvironmentRegistrar.class })
public @interface EtcdUpdateEnvironmentRepository
{
    String enable() default "${ENABLE_ENV_UPDATES:true}";

    String storePath() default "${ETCD_STORE_PATH}";

    String loadValues() default "${ENABLE_LOAD_ENV_UPDATE_PROPERTIES:true}";
}
