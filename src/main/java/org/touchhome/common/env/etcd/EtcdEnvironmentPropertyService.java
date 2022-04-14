package org.touchhome.common.env.etcd;

import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.DeleteResponse;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.PutResponse;
import io.etcd.jetcd.lock.LockResponse;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.touchhome.common.env.EnvironmentPropertyHolder;
import org.touchhome.common.env.EnvironmentPropertyModel;
import org.touchhome.common.env.EnvironmentPropertyService;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.touchhome.common.env.WebDocEnvironmentBeanFieldScannerBeanPostProcessor.springConvertValue;

/**
 * Etcd property services create/update properties from etcd server and listen for changes
 * Required property ETCD_ENDPOINTS which is url to etcd server i.e. http://localhost:2380
 * Required property ETCD_STORE_PATH which is prefix or path for all properties i.e. /dev/infrastructure/web-services/
 */
@Log4j2
public class EtcdEnvironmentPropertyService implements EnvironmentPropertyService {
    // environment key for storing etcd endpoint url
    public static final String ETCD_ENDPOINTS = "ETCD_ENDPOINTS";

    // environment key for storing etcd user name
    private static final String ETCD_AUTH_USER = "ETCD_AUTH_USER";

    // environment key for storing etcd user password
    private static final String ETCD_AUTH_PASSWORD = "ETCD_AUTH_PASSWORD";

    // environment key for storing etcd prefix path
    public static final String ETCD_STORE_PATH = "ETCD_STORE_PATH";

    // environment key for fetching verify certificates of TLS-enabled secure servers using this CA bundle
    public static final String ETCD_SSL_CA_CERTIFICATE = "ETCD_SSL_CERT_CA_CERT";

    // environment key for fetching identify secure client using this TLS certificate file
    private static final String ETCD_SSL_CLIENT_PUBLIC_CERTIFICATE = "ETCD_SSL_CERT_CLIENT_PUB_CERT";

    // environment key for fetching identify secure client using this TLS key file
    private static final String ETCD_SSL_CLIENT__PRIVATE_CERTIFICATE = "ETCD_SSL_CERT_CLIENT_PRV_CERT";

    // environment key for fetching certificates CN name
    public static final String ETCD_SSL_CERT_AUTHORITY = "ETCD_SSL_CERT_AUTHORITY";

    // environment key for fetching maximum message size
    private static final String ETCD_MAX_INBOUND_MESSAGE_SIZE = "ETCD_MAX_INBOUND_MESSAGE_SIZE";

    private static final Integer ETCD_WATCH_HISTORY_LIMIT = 100;

    private static Client client;

    private static Boolean etcdAvailable;

    @Setter
    private String storePath;

    @Setter
    private boolean loadValues;

    private Watch.Watcher watch;

    private Map<String, List<BiConsumer<String, Object>>> watchProperties = new HashMap<>();

    private Map<String, List<BiConsumer<String, Object>>> prefixWatchProperties = new HashMap<>();

    private Map<String, EtcdStat.EtcdLockInfo> lockInfo = new HashMap<>();

    private Map<String, List<EtcdStat.EtcdPropertyUpdate>> watchHistory = new HashMap<>();

    @Getter
    private EnvironmentPropertyHolder env;

    @Override
    public void afterPropertiesSet(EnvironmentPropertyHolder env) {
        this.env = env;
        this.env.getProperties().put(ETCD_ENDPOINTS,
                new EnvironmentPropertyModel(ETCD_ENDPOINTS, String.class, "Etcd url",
                        env.getProperty(ETCD_ENDPOINTS)));

        log.info("Initialise etcd updatable property service: storePath: <{}>, loadValues: <{}>", storePath,
                loadValues);
    }

    /**
     * Update property environment 'keyStr' with new value 'valueObj'
     * This method only fires changes into etcd. Properties on instances changes by watcher.
     * If env. ETCD_STORE_PATH found, prepend it to 'keyStr'
     *
     * @throws IllegalArgumentException may be thrown in such cases:
     *                                  1. keyStr is null
     *                                  2. valueObj is null
     *                                  3. keyStr is not updatable(not @WebDocEnvironemnt(...) found or wasn't added
     *                                  manually)
     *                                  4. valueObj can't be converted into class type that required for keyStr
     */
    @Override
    public String updateProperty(String keyStr, Object valueObj, boolean fetchPreviousValue, Long leaseId)
            throws ExecutionException, InterruptedException {
        keyStr = storePath + keyStr;

        KV kvClient = getClient().getKVClient();
        ByteSequence key = bytesOf(keyStr);

        ByteSequence bsValue = bytesOf(valueObj.toString());

        PutOption.Builder builder = PutOption.newBuilder();
        if (fetchPreviousValue) {
            builder.withPrevKV();
        }
        if (leaseId != null) {
            builder.withLeaseId(leaseId);
        }

        PutResponse putResponse = kvClient.put(key, bsValue, builder.build()).get();

        if (fetchPreviousValue) {
            // log notification that property was changed
            String value = getValue(putResponse.getPrevKv());
            log.debug("Etcd update property: <{}>. New value: <{}>. Previous values:<{}>", keyStr, valueObj, value);
            return value;
        }
        return null;
    }

    @Override
    public void updateProperties(Map<String, String> keyValue) {
        KV kvClient = getClient().getKVClient();

        List<CompletableFuture<PutResponse>> futures = new ArrayList<>(keyValue.size());
        for (Map.Entry<String, String> entry : keyValue.entrySet()) {
            futures.add(kvClient.put(bytesOf(storePath + entry.getKey()), bytesOf(entry.getValue())));
        }
    }

    @Override
    public <T> Map<String, T> getProperties(String prefix, boolean removeKeyPrefix, Function<byte[], T> valueConverter,
                                            Integer limit) throws ExecutionException, InterruptedException {
        KV kvClient = getClient().getKVClient();
        ByteSequence keyPath = bytesOf(storePath + prefix);
        GetOption.Builder getOptionBuilder = GetOption.newBuilder().withPrefix(keyPath);
        if (limit != null) {
            getOptionBuilder.withLimit(limit);
        }

        GetResponse response = kvClient.get(keyPath, getOptionBuilder.build()).get();

        return response.getKvs().stream().collect(Collectors.toMap(o -> {
            String key = toString(o.getKey());
            return removeKeyPrefix ? key.substring((storePath + prefix).length()) : key;
        }, keyValue -> valueConverter.apply(keyValue.getValue().getBytes())));
    }

    @Override
    @SneakyThrows
    public <T> String getProperty(String key, Function<byte[], T> valueConverter) {
        KV kvClient = getClient().getKVClient();

        GetResponse response = kvClient.get(bytesOf(storePath + key)).get();
        if (response.getCount() > 0) {
            return getValue(response.getKvs().get(0));
        }
        return null;
    }

    @Override
    public String removeProperty(String name, boolean prefix) throws ExecutionException, InterruptedException {
        ByteSequence key = bytesOf(storePath + name);
        KV kvClient = getClient().getKVClient();
        DeleteOption.Builder builder = DeleteOption.newBuilder().withPrevKV(!prefix);
        if (prefix) {
            builder.withPrefix(key);
        }

        DeleteResponse deleteResponse = kvClient.delete(key, builder.build()).get();
        List<KeyValue> prevKvs = deleteResponse.getPrevKvs();
        if (prevKvs.isEmpty()) {
            return null;
        }
        String value = getValue(prevKvs.get(0));
        log.debug("Etcd remove property: <{}>. Old value:<{}>", storePath + name, value);

        return value;
    }

    private String getValue(KeyValue keyValue) {
        return toString(keyValue.getValue()).split(":")[0];
    }

    private String toString(ByteSequence byteSequence) {
        return byteSequence.toString(UTF_8);
    }

    /**
     * Register new listener for property 'key'
     *
     * @param key         - property name
     * @param asPrefix    - detect to listen all keys that starts with 'key'
     * @param type        - property type
     * @param description - property description
     * @param consumer    - handler that calls on new data income
     */
    @Override
    @SneakyThrows
    public <T> void addPropertyListener(String key, boolean asPrefix, Class<T> type, String description,
                                        BiConsumer<String, T> consumer) {
        log.info("Etcd add property listener for key {} {}", key, asPrefix ? "as prefix" : "");

        Map<String, List<BiConsumer<String, Object>>> propertyHolder = asPrefix ?
                this.prefixWatchProperties :
                this.watchProperties;
        startWatcherIfRequired();

        propertyHolder.putIfAbsent(key, new ArrayList<>());
        propertyHolder.get(key).add((BiConsumer<String, Object>) consumer);

        if (loadValues && env.containsProperty(key)) {
            GetResponse response = getClient().getKVClient().get(bytesOf(storePath + key)).get();
            if (response.getCount() > 0) {
                String value = getValue(response.getKvs().get(0));
                callListeners(key, propertyHolder.get(key), value, true);
            }
        }
    }

    /**
     * Return list of properties that may be updated and listeners are registered
     */
    @Override
    public Collection<EnvironmentPropertyModel> getUpdatableProperties() {
        Map<String, EnvironmentPropertyModel> propertyModels = new HashMap<>();

        propertyModels.putAll(watchProperties.keySet().stream().filter(p -> env.containsProperty(p))
                .collect(Collectors.toMap(p -> p, p -> env.getProperties().get(p))));

        return propertyModels.values();
    }

    @Override
    @SneakyThrows
    public long createLease(String id, long leaseTTL) {
        log.debug("Etcd create lease with id: <{}>, ttl: <{}> sec", id, leaseTTL / 1000);
        return getClient().getLeaseClient().grant(leaseTTL / 1000).get().getID();
    }

    @Override
    @SneakyThrows
    public long refreshLeaseTTL(long leaseId) {
        log.debug("Etcd refresh lease with id: <{}>", leaseId);
        return getClient().getLeaseClient().keepAliveOnce(leaseId).get().getTTL();
    }

    @Override
    public <T> T synchronizeWithLock(String key, long ttl, long acquireLockTimeout, boolean releaseLockOnFinish,
                                     Supplier<T> handler, Supplier<T> unableToLockHandler) {
        Long leaseId = lockWithTime(key, ttl, acquireLockTimeout);
        if (leaseId == null) {
            return unableToLockHandler.get();
        }
        try {
            return handler.get();
        } catch (Exception ex) {
            log.error("Error during task execution, executing fallback", ex);
            return unableToLockHandler.get();
        } finally {
            if (releaseLockOnFinish) {
                unlock(leaseId);
                this.lockInfo.remove(key);
            }
        }
    }

    /**
     * Check if etcd endpoint exists and available.
     * To determine if etcd is available, application must have ETCD_ENDPOINTS env. set and
     * either ETCD_AUTH_USER,ETCD_AUTH_PASSWORD set or ETCD_SSL_CA_CERTIFICATE,ETCD_SSL_CERT_AUTHORITY set
     * Also ETCD_SSL_CERT_CLIENT_PUB_CERT and ETCD_SSL_CERT_CLIENT_PRV_CERT env are optional
     *
     * @param env system environments
     * @return is etcd is available
     */
    public static boolean isEtcdAvailable(Environment env) {
        if (etcdAvailable == null) {
            etcdAvailable = false;
            if (!env.containsProperty(ETCD_ENDPOINTS)) {
                log.error("Etcd endpoint not found. Skip etcd configuration.");
            } else {
                String etcdEndpoints = env.getRequiredProperty(ETCD_ENDPOINTS);
                log.info("Etcd endpoint found <{}>. Checking availability", etcdEndpoints);
                try {
                    Integer maxTimeout = env.getProperty("ETCD_MAX_CHECK_AVAILABILITY_TIMEOUT", Integer.class, 60);
                    getClient(env).getKVClient().get(bytesOf("alive")).get(maxTimeout, TimeUnit.SECONDS);
                    log.info("Etcd endpoint <{}> available.", etcdEndpoints);
                    etcdAvailable = true;
                } catch (Exception ex) {
                    log.error("Unable instantiate client for etcd service.", ex);
                }
            }
        }
        return etcdAvailable;
    }

    /**
     * Create new watcher on all keys or keys that started with 'ETCD_STORE_PATH' if specified
     */
    private void startWatcherIfRequired() {
        if (watch == null) {
            // listen all keys or with prefix depend on storePath
            WatchOption watchOption = WatchOption.newBuilder().withPrefix(bytesOf(this.storePath)).build();

            watch = getClient().getWatchClient().watch(bytesOf(""), watchOption,
                    watchResponse -> watchResponse.getEvents().forEach(this::handleEvent));
        }
    }

    /**
     * Handle income event with specified property 'key' that contains updated value
     */
    private void handleEvent(WatchEvent event) {
        KeyValue kv = event.getKeyValue();
        String key = toString(kv.getKey());
        String value = getValue(kv);
        log.debug("Etcd received watch event. Key: <{}>. Value: <{}>", key, value);
        if (key != null) {
            // update key only if it's started with storePath, otherwise skip handlers
            if (key.startsWith(this.storePath)) {
                key = key.substring(this.storePath.length());
            } else {
                return;
            }

            // search by equality
            if (watchProperties.containsKey(key)) {
                callListeners(key, watchProperties.get(key), value, false);
            }
            // search by prefix
            for (Map.Entry<String, List<BiConsumer<String, Object>>> entry : this.prefixWatchProperties.entrySet()) {
                if (key.startsWith(entry.getKey())) {
                    callListeners(key, entry.getValue(), value, false);
                }
            }
        }
    }

    /**
     * Invoke registered listeners by specified key
     */
    private void callListeners(String key, List<BiConsumer<String, Object>> listeners, String value,
                               boolean loadValue) {
        try {
            Object convertedValue = value;
            EnvironmentPropertyModel model = env.getProperties().get(key);
            if (model != null) {
                if (Objects.equals(model.getValue(), value) || listeners == null) {
                    return;
                }
                // convert value
                convertedValue = springConvertValue(value, key);

                // update properties map value
                model.setValue(value);
            }
            log.debug("Etcd got watch update for ket <{}> with new value <{}>", key, convertedValue);
            // call all listeners
            for (BiConsumer<String, Object> listener : listeners) {
                listener.accept(key, convertedValue);
            }
            this.watchHistory.putIfAbsent(key, new ArrayList<>());
            addToListWithLimit(this.watchHistory.get(key), new EtcdStat.EtcdPropertyUpdate(convertedValue));
        } catch (Exception ex) {
            log.error("Unable update environment <{}> with new value <{}>", key, value, ex);
            if (loadValue && env.getProperties().containsKey(key)) {
                env.getProperties().get(key)
                        .setErrorValue(value + "~~~" + Optional.ofNullable(ex.getCause()).orElse(ex).getMessage());
            }
        }
    }

    /**
     * Validate that keyStr environment property exists and valueObj may be converted to expected type.
     */
    public void validateValueObjType(String keyStr, Object valueObj) {
        List<BiConsumer<String, Object>> watchProperty = this.watchProperties.get(keyStr);
        if (watchProperty == null) {
            Optional<Map.Entry<String, List<BiConsumer<String, Object>>>> optional = this.prefixWatchProperties
                    .entrySet().stream().filter(e -> keyStr.startsWith(e.getKey())).findAny();
            if (optional.isPresent()) {
                watchProperty = optional.get().getValue();
            }
        }
        if (watchProperty == null) {
            throw new IllegalArgumentException("Unable handle property: " + keyStr + ". Property is not updatable.");
        }
    }

    private Client getClient() {
        return getClient(env);
    }

    /**
     * Creates etcd client.
     * Methods supports few Etcd Security model. https://coreos.com/etcd/docs/latest/op-guide/security.html
     * <p>
     * 1. Basic Authentication
     * requires env properties:
     * ETCD_AUTH_USER     - user name,
     * ETCD_AUTH_PASSWORD - user password
     * <p>
     * 2. Client-to-server transport security with HTTPS
     * requires env properties:
     * ETCD_SSL_CERT_CA_CERT   - verify certificates of TLS-enabled secure servers using this CA bundle
     * ETCD_SSL_CERT_AUTHORITY - certificates CN name
     * <p>
     * 3. Client-to-server authentication with HTTPS client certificates
     * requires env properties:
     * ETCD_SSL_CERT_CA_CERT, ETCD_SSL_CERT_AUTHORITY as described in sec.2
     * ETCD_SSL_CERT_CLIENT_PUB_CERT - identify secure client using this TLS certificate file
     * ETCD_SSL_CERT_CLIENT_PRV_CERT - identify secure client using this TLS key file
     */
    @SneakyThrows
    private static Client getClient(PropertyResolver env) {
        if (client == null) {
            String[] endpoints = env.getRequiredProperty(ETCD_ENDPOINTS).split(",");
            ClientBuilder clientBuilder = Client.builder().endpoints(endpoints);

            if (env.containsProperty(ETCD_MAX_INBOUND_MESSAGE_SIZE)) {
                clientBuilder.maxInboundMessageSize(env.getProperty(ETCD_MAX_INBOUND_MESSAGE_SIZE, Integer.class));
            }

            // check user/password security model
            if (env.containsProperty(ETCD_AUTH_USER) && env.containsProperty(ETCD_AUTH_PASSWORD)) {
                clientBuilder.user(bytesOf(env.getRequiredProperty(ETCD_AUTH_USER)))
                        .password(bytesOf(env.getRequiredProperty(ETCD_AUTH_PASSWORD)));
            }
            // check Client-to-server transport security with HTTPS
            else if (env.containsProperty(ETCD_SSL_CA_CERTIFICATE)) {
                // validate that file ETCD_SSL_CA_CERTIFICATE exists
                validFiles(env, ETCD_SSL_CA_CERTIFICATE);

                // Configure ssl certificates for client
                SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient()
                        .trustManager(new File(env.getRequiredProperty(ETCD_SSL_CA_CERTIFICATE))); // ca.pem

                // check Client-to-server authentication with HTTPS client certificates

                // If etcd server configured with using option '--client-cert-auth' client requires client's
                // pub/private keys.
                if (env.containsProperty(ETCD_SSL_CLIENT_PUBLIC_CERTIFICATE) && env
                        .containsProperty(ETCD_SSL_CLIENT__PRIVATE_CERTIFICATE)) {
                    // validate that files exists and readable
                    validFiles(env, ETCD_SSL_CLIENT_PUBLIC_CERTIFICATE, ETCD_SSL_CLIENT__PRIVATE_CERTIFICATE);

                    sslContextBuilder.keyManager(new File(env.getRequiredProperty(ETCD_SSL_CLIENT_PUBLIC_CERTIFICATE)),
                            new File(env.getRequiredProperty(ETCD_SSL_CLIENT__PRIVATE_CERTIFICATE)));
                }
                clientBuilder.authority(env.getRequiredProperty(ETCD_SSL_CERT_AUTHORITY))
                        .sslContext(sslContextBuilder.build());
            }

            client = clientBuilder.build();
            return client;
        }
        return client;
    }

    /**
     * Check if etcd certificate files are exists and readable
     */
    private static void validFiles(PropertyResolver env, String... filesKeys) {
        for (String fileKey : filesKeys) {
            File file = new File(env.getRequiredProperty(fileKey));
            if (!file.exists())
                throw new IllegalArgumentException("File: " + file.getName() + " not exists");
            if (!file.canRead())
                throw new IllegalArgumentException("File: " + file.getName() + " not readable");
        }
    }

    private Long lockWithTime(String lockName, Long leaseTTL, Long maxLockTimeout) {
        Long leaseId = null;
        try {
            log.trace("Etcd try lock variable <{}> with ttl: <{}>", lockName, leaseTTL);
            leaseId = getClient().getLeaseClient().grant(leaseTTL / 1000).get().getID();
            log.debug("Etcd created leaseId <{}> with ttl: <{}>", leaseId, leaseTTL);
            LockResponse lockResponse = getClient().getLockClient()
                    .lock(ByteSequence.from(lockName.getBytes()), leaseId).get(maxLockTimeout, TimeUnit.MILLISECONDS);
            log.debug("Etcd created key: <{}> that attached to lease: <{}> with leaseTTL: <{}> and maxLockTimeout: "
                    + "<{}>", lockName, leaseId, leaseTTL, maxLockTimeout);

            if (this.lockInfo.containsKey(lockName)) {
                log.error("Something went wrong with etcd locking system");
            }
            this.lockInfo.put(lockName, new EtcdStat.EtcdLockInfo(lockName, leaseTTL, maxLockTimeout, leaseId));
            return lockResponse == null ? null : leaseId;
        } catch (TimeoutException ex) {
            // This mean another instance locked key.
            log.error("Etcd acquire lock timeout exceeded for lockName <{}>.", lockName);
            // revoke lease
            unlock(leaseId);
        } catch (Exception ex) {
            // this is really unexpected error
            log.error("Etcd lock unknown exception.", ex);
            unlock(leaseId);
        }
        return null;
    }

    private void unlock(Long leaseId) {
        if (leaseId != null) {
            try {
                // with revoking lease, all associated keys would be removed also
                getClient().getLeaseClient().revoke(leaseId);
            } catch (Exception e) {
                log.error("Etcd unlock unknown exception", e);
            }
        }
    }

    public static ByteSequence bytesOf(final String string) {
        return ByteSequence.from(string, UTF_8);
    }

    @Override
    public EtcdStat getEtcdStat() {
        return new EtcdStat().setEtcdAvailable(true).setLockInfo(this.lockInfo).setWatchHistory(this.watchHistory);
    }

    /**
     * Add item to collection with removing oldest one if reach limitation
     */
    private static <T, C extends List<T>> void addToListWithLimit(C list, T itemToAdd) {
        list.add(itemToAdd);
        while (list.size() > EtcdEnvironmentPropertyService.ETCD_WATCH_HISTORY_LIMIT) {
            list.remove(0);
        }
    }
}
