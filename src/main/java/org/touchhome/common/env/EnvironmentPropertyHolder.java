package org.touchhome.common.env;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;
import org.touchhome.common.env.etcd.EtcdStat;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.touchhome.common.env.etcd.EtcdEnvironmentPropertyService.ETCD_ENDPOINTS;

@Log4j2
@Component
public class EnvironmentPropertyHolder implements PropertyResolver
{
    @Getter private final EnvironmentPropertyService environmentPropertyService;

    @Getter private final Map<String, EnvironmentPropertyModel> properties = new TreeMap<>();

    private final ConversionService conversionService = DefaultConversionService.getSharedInstance();

    private final Environment env;

    public EnvironmentPropertyHolder(Environment env,
                                     @Autowired(required = false) EnvironmentPropertyService environmentPropertyService)
    {
        this.env = env;
        this.environmentPropertyService = environmentPropertyService == null ? new EnvironmentPropertyService()
        {
        } : environmentPropertyService;
        this.environmentPropertyService.afterPropertiesSet(this);

        for( String key : new String[] { ETCD_ENDPOINTS, "MONGODB_NAME", "MONGODB_URI", "RABBITMQ_URI", "ENVIRONMENT",
                "HOSTNAME" } )
        {
            String envValue = env.getProperty(key);
            if( envValue != null )
            {
                // replace uses for hiding credentials from value
                log.info("Environment <{}> - <{}>", key, hideSensitiveData(envValue));
            }
        }
    }

    @Override
    public boolean containsProperty(String key)
    {
        return env.containsProperty(key) || properties.containsKey(key);
    }

    @Override
    public String getProperty(String key)
    {
        return getProperty(key, String.class, null);
    }

    @Override
    public String getProperty(String key, String defaultValue)
    {
        return getProperty(key, String.class, defaultValue);
    }

    @Override
    public <T> T getProperty(String key, Class<T> aClass)
    {
        return getProperty(key, aClass, null);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType, T t)
    {
        T value;
        if( properties.containsKey(key) )
        {
            value = (T)properties.get(key).getValue();
        }
        else
        {
            value = env.getProperty(key, targetType, t);
            if( value != null )
            {
                putProperty(key, value);
            }
        }
        return conversionService.convert(value, targetType);
    }

    private <T> T putProperty(String key, T value)
    {
        return putProperty(key, value, value == null ? null : value.getClass(), null);
    }

    <T> T putProperty(String key, T value, Class type, String description)
    {
        if( value instanceof String )
        {
            value = (T)hideSensitiveData((String)value);
        }
        if( properties.containsKey(key) )
        {
            properties.get(key).setValue(value.toString());
        }
        else
        {
            properties.put(key, new EnvironmentPropertyModel(key, type, description, value.toString()));
        }
        return value;
    }

    @Override
    public String getRequiredProperty(String key) throws IllegalStateException
    {
        return getRequiredProperty(key, String.class);
    }

    @Override
    public <T> T getRequiredProperty(String key, Class<T> aClass) throws IllegalStateException
    {
        if( properties.containsKey(key) )
        {
            return (T)properties.get(key).getValue();
        }
        return putProperty(key, env.getRequiredProperty(key, aClass));
    }

    @Override
    public String resolvePlaceholders(String key)
    {
        return putProperty(key, env.resolvePlaceholders(key));
    }

    @Override
    public String resolveRequiredPlaceholders(String key) throws IllegalArgumentException
    {
        return putProperty(key, env.resolveRequiredPlaceholders(key));
    }

    /**
     * Update property value using EnvironmentPropertyService implementation.
     * If no implementation found throws IllegalStateException("Property update service not available");
     *
     * @param key   - property key to update
     * @param value - new property value
     * @throws ExecutionException   if this future completed exceptionally
     * @throws InterruptedException if the current thread was interrupted
     */
    public void updateProperty(String key, Object value) throws ExecutionException, InterruptedException
    {
        environmentPropertyService.updateProperty(key, value, true, true, null);
    }

    public void updateProperties(Map<String, String> keyValue)
    {
        environmentPropertyService.updateProperties(keyValue);
    }

    /**
     * Add property listener to EnvironmentPropertyService implementation. If no implementation found - just skip
     * handling
     *
     * @param key         - property key
     * @param type        - property required type
     * @param description - property description
     * @param consumer    - property key handler
     * @param <T>         type of environment property
     */
    public <T> void addPropertyListener(String key, Class<T> type, String description, Consumer<T> consumer)
    {
        environmentPropertyService.addPropertyListener(key, false, type, description, (s, t) -> consumer.accept(t));
    }

    /**
     * Add property listener to EnvironmentPropertyService implementation. If no implementation found - just skip
     * handling. Listeners registers to all keys that starts with prefix
     *
     * @param prefix      - property prefix
     * @param type        - property required type
     * @param description - property description
     * @param consumer    - property key handler
     * @param <T>         type of environment property
     */
    public <T> void addPrefixPropertyListener(String prefix, Class<T> type, String description,
                                              BiConsumer<String, T> consumer)
    {
        environmentPropertyService.addPropertyListener(prefix, true, type, description, consumer);
    }

    /**
     * Return all updatable properties. Updatable properties is properties that were added to service via
     * addPropertyListener or addPrefixPropertyListener
     *
     * @return list of updatable environments
     */
    public Collection<EnvironmentPropertyModel> getUpdatableProperties()
    {
        return environmentPropertyService.getUpdatableProperties();
    }

    public EtcdStat getEtcdStat()
    {
        return environmentPropertyService.getEtcdStat();
    }

    public void removeProperty(String name, boolean prefix) throws ExecutionException, InterruptedException
    {
        environmentPropertyService.removeProperty(name, prefix);
    }

    public static String hideSensitiveData(String value)
    {
        return value.replaceFirst("://.*@", "//:*****:*****@");
    }
}
