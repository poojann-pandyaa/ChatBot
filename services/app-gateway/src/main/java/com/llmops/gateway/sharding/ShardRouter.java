package com.llmops.gateway.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Utility to calculate shard index (hash modulo 2) and bind target
 * read/write route keys to the thread context.
 */
@Component
public class ShardRouter {

    private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);

    /**
     * Determines shard index for a given userId.
     */
    public int getShardIndex(String userId) {
        if (userId == null) {
            return 0;
        }
        int index = Math.abs(userId.hashCode()) % 2;
        log.debug("UserId {} resolved to shard index {}", userId, index);
        return index;
    }

    /**
     * Binds the thread context to a write route on the primary database for the given user.
     */
    public void bindWriteRoute(String userId) {
        int shardIndex = getShardIndex(userId);
        DataSourceContextHolder.RouteKey key = (shardIndex == 0)
                ? DataSourceContextHolder.RouteKey.SHARD_0_WRITE
                : DataSourceContextHolder.RouteKey.SHARD_1_WRITE;
        DataSourceContextHolder.setRoute(key);
    }

    /**
     * Binds the thread context to a read route on the replica database for the given user.
     */
    public void bindReadRoute(String userId) {
        int shardIndex = getShardIndex(userId);
        DataSourceContextHolder.RouteKey key = (shardIndex == 0)
                ? DataSourceContextHolder.RouteKey.SHARD_0_READ
                : DataSourceContextHolder.RouteKey.SHARD_1_READ;
        DataSourceContextHolder.setRoute(key);
    }
}
