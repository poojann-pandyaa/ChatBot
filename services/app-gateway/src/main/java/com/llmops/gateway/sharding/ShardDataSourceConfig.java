package com.llmops.gateway.sharding;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class that instantiates database connection pools for shards
 * and registers them under a dynamic routing data source.
 */
@Configuration
public class ShardDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ShardDataSourceConfig.class);

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/chatbot_db}")
    private String primaryUrl;

    @Value("${spring.datasource.username:postgres}")
    private String username;

    @Value("${spring.datasource.password:SuperSecurePostgresPassword123}")
    private String password;

    @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    // Default replica to primary if not specified (graceful fallback)
    @Value("${replica.datasource.url:jdbc:postgresql://localhost:5433/chatbot_db}")
    private String replicaUrl;

    @Bean
    @Primary
    public DataSource dataSource() {
        log.info("Initializing Sharded + Replica routing DataSource...");

        // 1. Instantiating 4 physical data sources (2 shards x 2 endpoints)
        DataSource shard0Write = createHikariDataSource(
                getShardUrl(primaryUrl, "shard_0"), "shard-0-write-pool");
        DataSource shard1Write = createHikariDataSource(
                getShardUrl(primaryUrl, "shard_1"), "shard-1-write-pool");
        DataSource shard0Read = createHikariDataSource(
                getShardUrl(replicaUrl, "shard_0"), "shard-0-read-pool");
        DataSource shard1Read = createHikariDataSource(
                getShardUrl(replicaUrl, "shard_1"), "shard-1-read-pool");

        // 2. Registering in the AbstractRoutingDataSource map
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceContextHolder.RouteKey.SHARD_0_WRITE, shard0Write);
        targetDataSources.put(DataSourceContextHolder.RouteKey.SHARD_1_WRITE, shard1Write);
        targetDataSources.put(DataSourceContextHolder.RouteKey.SHARD_0_READ, shard0Read);
        targetDataSources.put(DataSourceContextHolder.RouteKey.SHARD_1_READ, shard1Read);

        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();
        routingDataSource.setTargetDataSources(targetDataSources);
        
        // Default route is Shard 0 Write
        routingDataSource.setDefaultTargetDataSource(shard0Write);
        routingDataSource.afterPropertiesSet();

        log.info("Routing DataSource initialized successfully with 4 targets.");
        return routingDataSource;
    }

    private String getShardUrl(String baseUrl, String schema) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "currentSchema=" + schema;
    }

    private DataSource createHikariDataSource(String jdbcUrl, String poolName) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driverClassName);
        ds.setPoolName(poolName);
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(2);
        ds.setIdleTimeout(30000);
        ds.setConnectionTimeout(20000);
        return ds;
    }
}
