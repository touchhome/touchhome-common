package org.touchhome.common.env;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.*;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.stereotype.Component;
import org.touchhome.common.util.SpringUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.springframework.util.StringUtils.hasLength;

/**
 * WebDocEnvironmentBeanPostProcessor scan bean's fields with annotation @WebDocEnvironment and if annotation has
 * value - refreshOnUpdate, registers listener in EnvironmentPropertyService for updating field's value
 */
@Log4j2
@Component
@AllArgsConstructor
public class WebDocEnvironmentBeanFieldScannerBeanPostProcessor implements BeanPostProcessor {
    @Getter
    private static final Map<String, EnvFieldContext> refreshOnUpdateFieldsToTarget = new HashMap<>();

    private final EnvironmentPropertyHolder environmentPropertyHolder;

    /**
     * Post processor looks through all beans and search fields that annotated with WebDocEnvironment
     *
     * @param bean     the new bean instance
     * @param beanName the name of the bean
     * @return original bean instance
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class clazz = bean.getClass();
        do {
            searchFieldsWebDocEnvironments(bean, clazz);
            clazz = clazz.getSuperclass();
        }
        while (clazz != null);
        return bean;
    }

    /**
     * Goes through all fields of bean annotated by @WebDocEnvironment and register listeners for them
     */
    private void searchFieldsWebDocEnvironments(Object bean, Class clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            WebDocEnvironment annotation = field.getAnnotation(WebDocEnvironment.class);
            Value valueAnnotation = field.getAnnotation(Value.class);

            if (annotation != null && (valueAnnotation != null || hasLength(annotation.propertyName()))) {
                // Search of property name by @WebDocEnvironment.propertyName first or @Value.name otherwise
                String propName = hasLength(annotation.propertyName()) ?
                        annotation.propertyName() :
                        SpringUtils.getSpringValuesPattern(SpringUtils.fixValue(valueAnnotation.value()))[0];

                if (annotation.refreshOnUpdate()) {
                    refreshOnUpdateFieldsToTarget
                            .put(propName, new EnvFieldContext(new DependencyDescriptor(field, false), bean));

                    EnvironmentPropertyModel epm = environmentPropertyHolder.getProperties().get(propName);
                    environmentPropertyHolder.addPropertyListener(propName, epm.getRawType(), epm.getDescription(),
                            value -> processFieldValue(field, bean, value));
                }
            }
        }
    }

    public static Object springConvertValue(Object value, String fieldKey) {
        ConfigurableListableBeanFactory beanFactory = WebDocEnvironmentFactoryBeanPostProcessor.beanFactory;
        if (value == null) {
            return null;
        }
        EnvFieldContext fieldContext = getRefreshOnUpdateFieldsToTarget().get(fieldKey);
        // in case if listen not @WebDocEnvironment value
        if (fieldContext == null) {
            return value;
        }
        Field field = fieldContext.getDependencyDescriptor().getField();
        Class<?> type = field.getType();

        if (type.isAssignableFrom(value.getClass())) {
            return value;
        }

        BeanExpressionResolver beanExpressionResolver = new StandardBeanExpressionResolver(
                beanFactory.getBeanClassLoader());
        DependencyDescriptor descriptor = fieldContext.getDependencyDescriptor();

        if (value instanceof String) {
            String strVal = beanFactory.resolveEmbeddedValue((String) value);
            value = beanExpressionResolver.evaluate(strVal, new BeanExpressionContext(beanFactory, null));
        }
        TypeConverter converter = beanFactory.getTypeConverter();
        try {
            return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());
        } catch (UnsupportedOperationException ex) {   // A custom TypeConverter which does not support TypeDescriptor resolution...
            converter.convertIfNecessary(value, type, field);
        }
        // resolveMultipleBeans
        String strValue = String.valueOf(value);
        if (type.isArray()) {
            String[] arrayValues = strValue.split(",");
            Object array = type.cast(Array.newInstance(type.getComponentType(), arrayValues.length));
            for (int i = 0; i < arrayValues.length; i++) {
                Array.set(array, i, arrayValues[i].trim());
            }
            return array;
        }
        return new SimpleTypeConverter().convertIfNecessary(value, type);
    }

    /**
     * Listener handler for field annotated with @WebDocEnvironment.
     * Updates field value via reflection with new value
     *
     * @param field    - beans field that would be changed
     * @param target   - field owner(bean)
     * @param newValue - new value to be injected into field
     */
    private Object processFieldValue(Field field, Object target, Object newValue) {
        boolean accessible = field.isAccessible();
        if (!accessible) {
            field.setAccessible(true);
        }
        try {
            Object prevValue = field.get(target);
            if (!Objects.equals(prevValue, newValue)) {
                field.set(target, newValue);
            }
            return prevValue;
        } catch (IllegalAccessException e) {
            log.error("Etcd unable update field <{}> of class <{}> with new property value <{}>", field,
                    target.getClass().getName(), newValue, e);
        } finally {
            if (!accessible) {
                field.setAccessible(false);
            }
        }
        return null;
    }

    static Object convertValue(String id, String value) {
        EnvFieldContext fieldContext = getRefreshOnUpdateFieldsToTarget().get(id);
        if (fieldContext != null) {
            Field field = fieldContext.getDependencyDescriptor().getField();
            if (field != null && (Collection.class.isAssignableFrom(field.getType()) || Map.class
                    .isAssignableFrom(field.getType()))) {
                return springConvertValue(value, id);
            }
        }
        return null;
    }

    @Getter
    @AllArgsConstructor
    private static class EnvFieldContext {
        private DependencyDescriptor dependencyDescriptor;

        private Object target;
    }
}
