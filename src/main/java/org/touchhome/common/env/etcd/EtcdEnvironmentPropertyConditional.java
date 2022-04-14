package org.touchhome.common.env.etcd;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Check if web-doc able to setup etcd listeners, etc..
 * 1. Check if environment 'ETCD_ENDPOINTS' is set
 * 2. Check if etcd endpoint available for fetching fake key
 */
public class EtcdEnvironmentPropertyConditional implements Condition
{
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata)
    {
        return EtcdEnvironmentPropertyService.isEtcdAvailable(context.getEnvironment());
    }
}
