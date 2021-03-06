package com.launchdarkly.client;

import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

/**
 * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a> for configuring the Redis-based persistent feature store.
 *
 * Obtain an instance of this class by calling {@link Components#redisFeatureStore()} or {@link Components#redisFeatureStore(URI)}.
 * Builder calls can be chained, for example:
 *
 * <pre><code>
 * FeatureStore store = Components.redisFeatureStore()
 *      .database(1)
 *      .caching(FeatureStoreCacheConfig.enabled().ttlSeconds(60))
 *      .build();
 * </code></pre>
 */
public final class RedisFeatureStoreBuilder implements FeatureStoreFactory {
  /**
   * The default value for the Redis URI: {@code redis://localhost:6379}
   * @since 4.0.0
   */
  public static final URI DEFAULT_URI = URI.create("redis://localhost:6379");
  
  /**
   * The default value for {@link #prefix(String)}.
   * @since 4.0.0
   */
  public static final String DEFAULT_PREFIX = "launchdarkly";
  
  /**
   * The default value for {@link #cacheTime(long, TimeUnit)} (in seconds).
   * @deprecated Use {@link FeatureStoreCacheConfig#DEFAULT}.
   * @since 4.0.0
   */
  public static final long DEFAULT_CACHE_TIME_SECONDS = FeatureStoreCacheConfig.DEFAULT_TIME_SECONDS;
  
  final URI uri;
  String prefix = DEFAULT_PREFIX;
  int connectTimeout = Protocol.DEFAULT_TIMEOUT;
  int socketTimeout = Protocol.DEFAULT_TIMEOUT;
  Integer database = null;
  String password = null;
  boolean tls = false;
  FeatureStoreCacheConfig caching = FeatureStoreCacheConfig.DEFAULT;
  boolean refreshStaleValues = false; // this and asyncRefresh are redundant with FeatureStoreCacheConfig, but are used by deprecated setters
  boolean asyncRefresh = false;
  JedisPoolConfig poolConfig = null;

  // These constructors are called only from Implementations
  RedisFeatureStoreBuilder() {
    this.uri = DEFAULT_URI;
  }
  
  RedisFeatureStoreBuilder(URI uri) {
    this.uri = uri;
  }
  
  /**
   * The constructor accepts the mandatory fields that must be specified at a minimum to construct a {@link com.launchdarkly.client.RedisFeatureStore}.
   *
   * @param uri the uri of the Redis resource to connect to.
   * @param cacheTimeSecs the cache time in seconds. See {@link RedisFeatureStoreBuilder#cacheTime(long, TimeUnit)} for more information.
   * @deprecated Please use {@link Components#redisFeatureStore(java.net.URI)}.
   */
  public RedisFeatureStoreBuilder(URI uri, long cacheTimeSecs) {
    this.uri = uri;
    this.cacheTime(cacheTimeSecs, TimeUnit.SECONDS);
  }

  /**
   * The constructor accepts the mandatory fields that must be specified at a minimum to construct a {@link com.launchdarkly.client.RedisFeatureStore}.
   *
   * @param scheme the URI scheme to use
   * @param host the hostname to connect to
   * @param port the port to connect to
   * @param cacheTimeSecs the cache time in seconds. See {@link RedisFeatureStoreBuilder#cacheTime(long, TimeUnit)} for more information.
   * @throws URISyntaxException if the URI is not valid
   * @deprecated Please use {@link Components#redisFeatureStore(java.net.URI)}.
   */
  public RedisFeatureStoreBuilder(String scheme, String host, int port, long cacheTimeSecs) throws URISyntaxException {
    this.uri = new URI(scheme, null, host, port, null, null, null);
    this.cacheTime(cacheTimeSecs, TimeUnit.SECONDS);
  }

  /**
   * Specifies the database number to use.
   * <p>
   * The database number can also be specified in the Redis URI, in the form {@code redis://host:port/NUMBER}. Any
   * non-null value that you set with {@link #database(Integer)} will override the URI.
   * 
   * @param database the database number, or null to fall back to the URI or the default
   * @return the builder
   * 
   * @since 4.7.0
   */
  public RedisFeatureStoreBuilder database(Integer database) {
    this.database = database;
    return this;
  }
  
  /**
   * Specifies a password that will be sent to Redis in an AUTH command.
   * <p>
   * It is also possible to include a password in the Redis URI, in the form {@code redis://:PASSWORD@host:port}. Any
   * password that you set with {@link #password(String)} will override the URI.
   * 
   * @param password the password
   * @return the builder
   * 
   * @since 4.7.0
   */
  public RedisFeatureStoreBuilder password(String password) {
    this.password = password;
    return this;
  }
  
  /**
   * Optionally enables TLS for secure connections to Redis.
   * <p>
   * This is equivalent to specifying a Redis URI that begins with {@code rediss:} rather than {@code redis:}.
   * <p>
   * Note that not all Redis server distributions support TLS.
   * 
   * @param tls true to enable TLS
   * @return the builder
   * 
   * @since 4.7.0
   */
  public RedisFeatureStoreBuilder tls(boolean tls) {
    this.tls = tls;
    return this;
  }
  
  /**
   * Specifies whether local caching should be enabled and if so, sets the cache properties. Local
   * caching is enabled by default; see {@link FeatureStoreCacheConfig#DEFAULT}. To disable it, pass
   * {@link FeatureStoreCacheConfig#disabled()} to this method.
   * 
   * @param caching a {@link FeatureStoreCacheConfig} object specifying caching parameters
   * @return the builder
   * 
   * @since 4.6.0
   */
  public RedisFeatureStoreBuilder caching(FeatureStoreCacheConfig caching) {
    this.caching = caching;
    return this;
  }
  
  /**
   * Deprecated method for setting the cache expiration policy to {@link FeatureStoreCacheConfig.StaleValuesPolicy#REFRESH}
   * or {@link FeatureStoreCacheConfig.StaleValuesPolicy#REFRESH_ASYNC}.
   *
   * @param enabled turns on lazy refresh of cached values
   * @return the builder
   * 
   * @deprecated Use {@link #caching(FeatureStoreCacheConfig)} and
   * {@link FeatureStoreCacheConfig#staleValuesPolicy(com.launchdarkly.client.FeatureStoreCacheConfig.StaleValuesPolicy)}.
   */
  public RedisFeatureStoreBuilder refreshStaleValues(boolean enabled) {
    this.refreshStaleValues = enabled;
    updateCachingStaleValuesPolicy();
    return this;
  }

  /**
   * Deprecated method for setting the cache expiration policy to {@link FeatureStoreCacheConfig.StaleValuesPolicy#REFRESH_ASYNC}.
   *
   * @param enabled turns on asynchronous refresh of cached values (only if {@link #refreshStaleValues(boolean)}
   * is also true)
   * @return the builder
   * 
   * @deprecated Use {@link #caching(FeatureStoreCacheConfig)} and
   * {@link FeatureStoreCacheConfig#staleValuesPolicy(com.launchdarkly.client.FeatureStoreCacheConfig.StaleValuesPolicy)}.
   */
  public RedisFeatureStoreBuilder asyncRefresh(boolean enabled) {
    this.asyncRefresh = enabled;
    updateCachingStaleValuesPolicy();
    return this;
  }

  private void updateCachingStaleValuesPolicy() {
    // We need this logic in order to support the existing behavior of the deprecated methods above:
    // asyncRefresh is supposed to have no effect unless refreshStaleValues is true
    if (this.refreshStaleValues) {
      this.caching = this.caching.staleValuesPolicy(this.asyncRefresh ?
          FeatureStoreCacheConfig.StaleValuesPolicy.REFRESH_ASYNC :
          FeatureStoreCacheConfig.StaleValuesPolicy.REFRESH);
    } else {
      this.caching = this.caching.staleValuesPolicy(FeatureStoreCacheConfig.StaleValuesPolicy.EVICT);
    }
  }
  
  /**
   * Optionally configures the namespace prefix for all keys stored in Redis.
   *
   * @param prefix the namespace prefix
   * @return the builder
   */
  public RedisFeatureStoreBuilder prefix(String prefix) {
    this.prefix = prefix;
    return this;
  }

  /**
   * Deprecated method for enabling local caching and setting the cache TTL. Local caching is enabled
   * by default; see {@link FeatureStoreCacheConfig#DEFAULT}.
   *
   * @param cacheTime the time value to cache for, or 0 to disable local caching
   * @param timeUnit the time unit for the time value
   * @return the builder
   * 
   * @deprecated use {@link #caching(FeatureStoreCacheConfig)} and {@link FeatureStoreCacheConfig#ttl(long, TimeUnit)}.
   */
  public RedisFeatureStoreBuilder cacheTime(long cacheTime, TimeUnit timeUnit) {
    this.caching = this.caching.ttl(cacheTime, timeUnit)
        .staleValuesPolicy(this.caching.getStaleValuesPolicy());
    return this;
  }

  /**
   * Optional override if you wish to specify your own configuration to the underlying Jedis pool.
   *
   * @param poolConfig the Jedis pool configuration.
   * @return the builder
   */
  public RedisFeatureStoreBuilder poolConfig(JedisPoolConfig poolConfig) {
    this.poolConfig = poolConfig;
    return this;
  }

  /**
   * Optional override which sets the connection timeout for the underlying Jedis pool which otherwise defaults to
   * {@link redis.clients.jedis.Protocol#DEFAULT_TIMEOUT}
   *
   * @param connectTimeout the timeout
   * @param timeUnit the time unit for the timeout
   * @return the builder
   */
  public RedisFeatureStoreBuilder connectTimeout(int connectTimeout, TimeUnit timeUnit) {
    this.connectTimeout = (int) timeUnit.toMillis(connectTimeout);
    return this;
  }

  /**
   * Optional override which sets the connection timeout for the underlying Jedis pool which otherwise defaults to
   * {@link redis.clients.jedis.Protocol#DEFAULT_TIMEOUT}
   *
   * @param socketTimeout the socket timeout
   * @param timeUnit the time unit for the timeout
   * @return the builder
   */
  public RedisFeatureStoreBuilder socketTimeout(int socketTimeout, TimeUnit timeUnit) {
    this.socketTimeout = (int) timeUnit.toMillis(socketTimeout);
    return this;
  }

  /**
   * Build a {@link RedisFeatureStore} based on the currently configured builder object.
   * @return the {@link RedisFeatureStore} configured by this builder.
   */
  public RedisFeatureStore build() {
    return new RedisFeatureStore(this);
  }
  
  /**
   * Synonym for {@link #build()}.
   * @return the {@link RedisFeatureStore} configured by this builder.
   * @since 4.0.0
   */
  public RedisFeatureStore createFeatureStore() {
    return build();
  }
}
