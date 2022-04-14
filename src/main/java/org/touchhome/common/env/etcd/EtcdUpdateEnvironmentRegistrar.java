package org.touchhome.common.env.etcd;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.touchhome.common.util.AnnotationReader;

@Log4j2
class EtcdUpdateEnvironmentRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware
{
    @Setter private Environment environment;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry)
    {
        AnnotationReader annotationReader = new AnnotationReader(environment, AnnotationAttributes
                .fromMap(metadata.getAnnotationAttributes(EtcdUpdateEnvironmentRepository.class.getName())));

        if( !annotationReader.getBoolean("enable") )
        {
            log.warn("Found disabled EtcdUpdateEnvironmentRepository");
        }
        else if( !EtcdEnvironmentPropertyService.isEtcdAvailable(environment) )
        {
            log.warn("Found EtcdUpdateEnvironmentRepository without etcd configuration");
        }
        else
        {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder
                    .rootBeanDefinition(EtcdEnvironmentPropertyService.class)
                    .addPropertyValue("loadValues", annotationReader.getValue("loadValues"))
                    .addPropertyValue("storePath", annotationReader.getValue("storePath"));
            registry.registerBeanDefinition("etcdEnvironmentPropertyService", builder.getBeanDefinition());
        }
    }
}
