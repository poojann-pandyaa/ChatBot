package com.llmops.gateway.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Concrete implementation of Spring's AbstractRoutingDataSource that routes
 * database queries based on the thread-local context key.
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(ShardRoutingDataSource.class);

    @Override
    protected Object determineCurrentLookupKey() {
        Object route = DataSourceContextHolder.getRoute();
        if (route == null) {
            log.error("CRITICAL: No database routing key bound to thread {}. A route must be explicitly set before any database operation.", Thread.currentThread().getName());
            throw new IllegalStateException("No database routing key bound to thread " + Thread.currentThread().getName());
        }
        return route;
    }
}
