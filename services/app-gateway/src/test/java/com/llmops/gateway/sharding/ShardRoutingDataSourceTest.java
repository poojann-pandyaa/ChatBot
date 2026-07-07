package com.llmops.gateway.sharding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ShardRoutingDataSourceTest {

    private ShardRoutingDataSource routingDataSource;

    @BeforeEach
    public void setUp() {
        routingDataSource = new ShardRoutingDataSource();
        DataSourceContextHolder.clear();
    }

    @AfterEach
    public void tearDown() {
        DataSourceContextHolder.clear();
    }

    @Test
    public void testDetermineCurrentLookupKey() {
        // Initially, route context should be null (will fallback to default target datasource)
        assertNull(routingDataSource.determineCurrentLookupKey());

        // Set route context to SHARD_0_WRITE
        DataSourceContextHolder.setRoute(DataSourceContextHolder.RouteKey.SHARD_0_WRITE);
        assertEquals(DataSourceContextHolder.RouteKey.SHARD_0_WRITE, routingDataSource.determineCurrentLookupKey());

        // Set route context to SHARD_1_READ
        DataSourceContextHolder.setRoute(DataSourceContextHolder.RouteKey.SHARD_1_READ);
        assertEquals(DataSourceContextHolder.RouteKey.SHARD_1_READ, routingDataSource.determineCurrentLookupKey());

        // Clear context
        DataSourceContextHolder.clear();
        assertNull(routingDataSource.determineCurrentLookupKey());
    }
}
