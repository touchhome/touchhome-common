package org.touchhome.common.env;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.stereotype.Component;
import org.touchhome.common.util.SpringUtils;

/**
 * WebDocEnvironmentFactoryBeanPostProcessor replaces DefaultListableBeanFactory's AutowireCandidateResolver class instance.
 * When DefaultListableBeanFactory resolves @Value annotation in method findValue(...), this class search for extra annotation WebDocEnvironment.class
 * and, if presents, add actual value to EnvironmentPropertyHolder bean.
 * i.e. for below bean current resolver finds 'insecure_value' field and put this property into EnvironmentPropertyHolder with system's or 'defaultContent' value,
 * but ommits 'secure_value' field
 * <pre class="code">
 *
 * &#064;Bean
 * public String getSomeData(@WebDocEnvironment @Value("${insecure_value:defaultContent}") String someValue, @Value("${secure_value:actualPwd}") String secure) {
 *   return someValue + encode(secureData);
 * }</pre>
 */
@Log4j2
@Component
public class WebDocEnvironmentFactoryBeanPostProcessor implements BeanFactoryPostProcessor {
    static ConfigurableListableBeanFactory beanFactory;

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        WebDocEnvironmentFactoryBeanPostProcessor.beanFactory = beanFactory;
        DefaultListableBeanFactory bf = (DefaultListableBeanFactory) beanFactory;
        bf.setAutowireCandidateResolver(new WebDocEnvironmentResolver(bf));
    }

    @Component
    @AllArgsConstructor
    private static class WebDocEnvironmentResolver extends ContextAnnotationAutowireCandidateResolver {
        private static EnvironmentPropertyHolder environmentPropertyHolder;

        private final DefaultListableBeanFactory bf;

        public Object getSuggestedValue(DependencyDescriptor descriptor) {
            Object value = this.findValue(descriptor);
            if (value == null) {
                MethodParameter methodParam = descriptor.getMethodParameter();
                if (methodParam != null) {
                    value = this.findValue(methodParam.getMethodAnnotations());
                }
            }

            return value;
        }

        Object findValue(DependencyDescriptor descriptor) {
            // search for annotation WebDocEnvironment.class, if detect - add property value to getEnvironmentPropertyHolder()
            AnnotationAttributes attr = AnnotatedElementUtils
                    .getMergedAnnotationAttributes(AnnotatedElementUtils.forAnnotations(descriptor.getAnnotations()),
                            WebDocEnvironment.class);
            Object value = super.findValue(descriptor.getAnnotations());

            if (attr != null && value instanceof String && attr.getBoolean("showInController")) {
                String propertyValue = bf.resolveEmbeddedValue((String) value);

                String strValue = SpringUtils.fixValue((String) value);
                String propName = SpringUtils.getSpringValuesPattern(strValue)[0];
                getEnvironmentPropertyHolder().putProperty(propName, propertyValue, descriptor.getDependencyType(),
                        attr.getString("description"));
                // logs all property with annotation @WebDocEnvironment
                log.info("Environment {} <{}> - <{}>", descriptor.getDependencyType().getSimpleName(), propName,
                        propertyValue);
            }

            return value;
        }

        // Lazy search bean, if project has no WebDocEnvironment annotations, holder not need at all
        private EnvironmentPropertyHolder getEnvironmentPropertyHolder() {
            if (environmentPropertyHolder == null) {
                environmentPropertyHolder = bf.getBean(EnvironmentPropertyHolder.class);
            }
            return environmentPropertyHolder;
        }
    }
}
