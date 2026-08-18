package com.llmops.gateway.sharding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;

public class ShardRouterTest {

    private ShardRouter shardRouter;

    @BeforeEach
    public void setUp() {
        shardRouter = new ShardRouter();
        DataSourceContextHolder.clear();
    }

    @AfterEach
    public void tearDown() {
        DataSourceContextHolder.clear();
    }

    @Test
    public void testGetShardIndex() {
        // Test null handling (defaults to 0)
        assertEquals(0, shardRouter.getShardIndex(null));

        // Test consistent hash modulo 2 routing
        String userA = "user_A";
        int expectedIndexA = Math.abs(Hashing.murmur3_32_fixed().hashString(userA, StandardCharsets.UTF_8).asInt()) % 2;
        assertEquals(expectedIndexA, shardRouter.getShardIndex(userA));

        String userB = "user_B";
        int expectedIndexB = Math.abs(Hashing.murmur3_32_fixed().hashString(userB, StandardCharsets.UTF_8).asInt()) % 2;
        assertEquals(expectedIndexB, shardRouter.getShardIndex(userB));
    }

    @Test
    public void testBindWriteRoute() {
        String userIdShard0 = "user_1";
        String userIdShard1 = "user_2";
        
        while (shardRouter.getShardIndex(userIdShard0) == shardRouter.getShardIndex(userIdShard1)) {
             userIdShard1 = userIdShard1 + "_diff";
        }

        int index0 = shardRouter.getShardIndex(userIdShard0);
        int index1 = shardRouter.getShardIndex(userIdShard1);

        assertNotEquals(index0, index1);

        // Bind Shard 0 Write
        shardRouter.bindWriteRoute(userIdShard0);
        assertEquals(
                (index0 == 0) ? DataSourceContextHolder.RouteKey.SHARD_0_WRITE : DataSourceContextHolder.RouteKey.SHARD_1_WRITE,
                DataSourceContextHolder.getRoute()
        );

        // Bind Shard 1 Write
        shardRouter.bindWriteRoute(userIdShard1);
        assertEquals(
                (index1 == 0) ? DataSourceContextHolder.RouteKey.SHARD_0_WRITE : DataSourceContextHolder.RouteKey.SHARD_1_WRITE,
                DataSourceContextHolder.getRoute()
        );
    }

    @Test
    public void testBindReadRoute() {
        String userIdShard0 = "user_1";
        int index0 = shardRouter.getShardIndex(userIdShard0);

        // Bind Shard 0 Read
        shardRouter.bindReadRoute(userIdShard0);
        assertEquals(
                (index0 == 0) ? DataSourceContextHolder.RouteKey.SHARD_0_READ : DataSourceContextHolder.RouteKey.SHARD_1_READ,
                DataSourceContextHolder.getRoute()
        );
    }
}
