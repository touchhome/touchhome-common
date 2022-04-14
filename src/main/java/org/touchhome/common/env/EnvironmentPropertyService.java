package org.touchhome.common.env;

import lombok.SneakyThrows;
import org.touchhome.common.env.etcd.EtcdStat;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.touchhome.common.env.WebDocEnvironmentBeanFieldScannerBeanPostProcessor.springConvertValue;


/**
 * Interface for all implementations for update/listen environment values
 */
public interface EnvironmentPropertyService
{
    /**
     * Fires update property value with key/value
     *
     * @param key              property name
     * @param value            property value
     * @param validateProperty - validate property key and value against available properties
     * @param leaseId          - lease id for property or null
     * @return previous value or null
     * @throws ExecutionException   if this future completed exceptionally
     * @throws InterruptedException if the current thread was interrupted
     */
    default String updateProperty(String key, Object value, boolean validateProperty, boolean fetchPreviousValue,
                                  Long leaseId) throws ExecutionException, InterruptedException
    {
        if( key == null )
        {
            throw new IllegalArgumentException("Property name must be not null");
        }

        if( value == null )
        {
            throw new IllegalArgumentException("Property: " + key + " must have value but got null");
        }
        if( validateProperty )
        {
            validateValueObjType(key, value);
            try
            {
                springConvertValue(value, key);
            }
            catch(Exception ex)
            {
                throw new IllegalArgumentException(
                        "Unable convert value: " + value + " for property: " + key + ". " + "Required type is: "
                                + getEnv().getProperties().get(key).getRawType().getName());
            }
        }
        return updateProperty(key, value, fetchPreviousValue, leaseId);
    }

    default String updateProperty(String key, Object value, boolean fetchPreviousValue, Long leaseId)
            throws ExecutionException, InterruptedException
    {
        throw new UnsupportedOperationException("Property update service not available");
    }

    default void validateValueObjType(String key, Object value)
    {
    }

    default void updateProperties(Map<String, String> keyValue)
    {
        throw new UnsupportedOperationException("Properties update service not available");
    }

    /**
     * Get properties by prefix
     *
     * @param prefix         - property key prefix
     * @param valueConverter - value converter
     * @param <T>            return type
     * @return map of key/value
     * @removeKeyPrefix - remove 'prefix' from returned keys
     */
    default <T> Map<String, T> getProperties(String prefix, boolean removeKeyPrefix, Function<byte[], T> valueConverter)
            throws ExecutionException, InterruptedException
    {
        return getProperties(prefix, removeKeyPrefix, valueConverter, null);
    }

    default <T> Map<String, T> getProperties(String prefix, boolean removeKeyPrefix, Function<byte[], T> valueConverter,
                                             Integer limit) throws ExecutionException, InterruptedException
    {
        throw new UnsupportedOperationException("Get properties service not available");
    }

    /**
     * Remove property by name.
     *
     * @param name - property name
     * @return previous value or null if property not found in etcd
     * @throws ExecutionException   if this future completed exceptionally
     * @throws InterruptedException if the current thread was interrupted
     */
    default String removeProperty(String name, boolean prefix) throws ExecutionException, InterruptedException
    {
        throw new UnsupportedOperationException("Property remove service not available");
    }

    default String removeProperty(String name) throws ExecutionException, InterruptedException
    {
        return removeProperty(name, false);
    }

    /**
     * Add property listeners for property with key
     *
     * @param key         property name
     * @param asPrefix    check if register property name as prefix
     * @param type        - property java type
     * @param description - property description
     * @param consumer    handler fires when property value changed
     * @param <T>         property java type generic
     */
    default <T> void addPropertyListener(String key, boolean asPrefix, Class<T> type, String description,
                                         BiConsumer<String, T> consumer)
    {
    }

    /**
     * Return all keys that were added via addPropertyListener() method
     *
     * @return list of updatable properties
     */
    default Collection<EnvironmentPropertyModel> getUpdatableProperties()
    {
        return Collections.emptyList();
    }

    /**
     * @param id       - lease id
     * @param leaseTTL - time to live in seconds
     * @return created lease id
     */
    @SneakyThrows
    default long createLease(String id, long leaseTTL)
    {
        throw new UnsupportedOperationException("Create leaseTTL service not available");
    }

    /**
     * Synchronize multiple instance code with etcd lock.
     * This is blocking method, use own thread implementation for async.
     * <p>
     * Method guarantee that handler.get() would be called by multiple applications:
     * 1. one by one without ordering if ttl &lt; acquireLockTimeout and handler.get() method execution time is lower
     * that ttl
     * 2. only one app instance would call handler.get() if ttl &gt; acquireLockTimeout and method execution time is
     * bigger that ttl. Other instances would receive unableToLockConsumer.run()
     * <p>
     * If you want scenario of running single method per multiple instances, but don't know how much time runnable
     * .run() takes, you may set ttl &gt; acquireLockTimeout and set releaseLockOnFinish as false. In this case even if
     * handler.get() finished earlier then ttl exceeded, lock wouldn't be released until ttl (other apps got
     * timeoutexception and unableToLockConsumer.run called instead)
     * <p>
     * ttl and acquireLockTimeout very important to understand and depend on code in runnable.
     * I.E.: If runnable method execution is heavy and seek to infinite but you are sure that all application
     * instances would be started in one minute, than make sense set ttl &gt; 60 i.e. 90sec, but acquireLockTimeout set
     * as 60. In this case first acquired lock would be executed and method runnable would be called, but all other
     * application instances would try acquire lock for 60 seconds without success and unableToLockConsumer will be
     * called then.
     * <p>
     * Do not recommend set ttl as Integer.MAX_VALUE. If ttl has too much value and jvm stopped abnormally this would
     * lead that subsequent application instances would not acquire lock until ttl is expired, because etcd would still
     * has lock on key
     *
     * @param handler             - method that would be called when app acquired lock
     * @param unableToLockHandler - method that would be called when app unable to acquire lock or
     *                            acquireLockTimeout exceed or etcd not available
     * @param key                 - unique key per multiple instances
     * @param ttl                 - At most time to live in milliseconds for lock.
     *                            Must be bigger than time need for evaluation codeToRun otherwise another app instance will be
     *                            invoked
     * @param acquireLockTimeout  - at most time in milliseconds that etcd would try acquire lock, otherwise
     *                            unableToLockConsumer would be called
     * @param releaseLockOnFinish - declare if lock would be released when handler.get() is executed. In most cases
     *                            should be true
     * @param <T>                 - type of return value
     * @return value returned by handler
     */
    default <T> T synchronizeWithLock(String key, long ttl, long acquireLockTimeout, boolean releaseLockOnFinish,
                                      Supplier<T> handler, Supplier<T> unableToLockHandler)
    {
        return unableToLockHandler.get();
    }

    /**
     * Retrieve all etcd statistic related to this application instance
     */
    default EtcdStat getEtcdStat()
    {
        return new EtcdStat();
    }

    default <T> String getProperty(String key, Function<byte[], T> valueConverter)
    {
        throw new UnsupportedOperationException("Get properties service not available");
    }

    default long refreshLeaseTTL(long leaseId)
    {
        throw new UnsupportedOperationException("Refresh LeaseTTL service not available");
    }

    default void afterPropertiesSet(EnvironmentPropertyHolder env)
    {

    }

    default EnvironmentPropertyHolder getEnv()
    {
        throw new UnsupportedOperationException("Get env not available");
    }
}
