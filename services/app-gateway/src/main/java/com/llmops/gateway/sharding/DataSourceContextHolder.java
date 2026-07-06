package com.llmops.gateway.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ThreadLocal context holder to determine the target datasource route
 * (e.g. SHARD_0_WRITE, SHARD_1_WRITE, SHARD_0_READ, SHARD_1_READ).
 */
public class DataSourceContextHolder {

    private static final Logger log = LoggerFactory.getLogger(DataSourceContextHolder.class);

    public enum RouteKey {
        SHARD_0_WRITE,
        SHARD_1_WRITE,
        SHARD_0_READ,
        SHARD_1_READ
    }

    private static final ThreadLocal<RouteKey> CONTEXT = new ThreadLocal<>();

    public static void setRoute(RouteKey routeKey) {
        log.debug("Setting datasource route key context to: {}", routeKey);
        CONTEXT.set(routeKey);
    }

    public static RouteKey getRoute() {
        return CONTEXT.get();
    }

    public static void clear() {
        log.debug("Clearing datasource route key context");
        CONTEXT.remove();
    }
}
