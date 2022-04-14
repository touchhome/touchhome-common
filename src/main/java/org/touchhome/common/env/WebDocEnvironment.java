package org.touchhome.common.env;

import java.lang.annotation.*;

/**
 * Annotation for fields which marked as @Value("${temp_dir}") to indicate that 'temp_dir'
 * environment property must be included into EnvironmentConfigurationController
 * <p>
 * If refreshOnUpdate is set and etcd environment properties are present, register new listener that updates bean field
 * when etcd watcher receive new values from etcd server
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Documented
public @interface WebDocEnvironment
{
    /**
     * Determine if environment variable should be visible in /environment properties.
     * Uses by WebDocEnvironmentFactoryBeanPostProcessor class as string property
     *
     * @return check if environment should be visible in /environment endpoint
     */
    boolean showInController() default true;

    /**
     * Uses in case of Etcd services is working. Check if value is true and add listeners to bean for overriding
     * values on income data
     *
     * @return check if environment should be included into etcd watching
     */
    boolean refreshOnUpdate() default false;

    /**
     * Environment description. Uses in host/environment/updatable for show property description.
     * Uses only if refreshOnUpdate is true
     *
     * @return environment description
     */
    String description() default "";

    /**
     * Environment name, if not specified, @Value(...) annotation would be searched and used for fetching property
     * name.
     *
     * @return environment name
     */
    String propertyName() default "";
}
