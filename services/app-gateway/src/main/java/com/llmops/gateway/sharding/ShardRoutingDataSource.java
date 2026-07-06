package com.llmops.gateway.sharding;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Concrete implementation of Spring's AbstractRoutingDataSource that routes
 * database queries based on the thread-local context key.
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.getRoute();
    }
}
