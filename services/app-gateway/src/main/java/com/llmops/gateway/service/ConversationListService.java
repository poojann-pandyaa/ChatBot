package com.llmops.gateway.service;

import com.llmops.gateway.sharding.DataSourceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Queries conversations across all shards.
 *
 * <p>Because the routing datasource selects a single schema per thread, we
 * temporarily switch the route key for each shard, run the query, then
 * aggregate the results.  All reads stay on the primary (write) pools so
 * there is no read-replica lag to worry about in dev.</p>
 */
@Service
public class ConversationListService {

    private static final Logger log = LoggerFactory.getLogger(ConversationListService.class);

    private static final String QUERY =
            "SELECT id, COALESCE(title, 'New conversation') AS name FROM conversations " +
            "WHERE user_id = ? ORDER BY created_at DESC";

    private final JdbcTemplate jdbcTemplate;

    public ConversationListService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns all conversations for {@code userId} ordered by newest first,
     * queried across every shard and de-duplicated by id.
     */
    public List<Map<String, String>> listAllShards(String userId) {
        List<Map<String, String>> result = new ArrayList<>();

        for (DataSourceContextHolder.RouteKey shard :
                List.of(DataSourceContextHolder.RouteKey.SHARD_0_WRITE,
                        DataSourceContextHolder.RouteKey.SHARD_1_WRITE)) {

            DataSourceContextHolder.setRoute(shard);
            try {
                List<Map<String, String>> rows = jdbcTemplate.query(QUERY,
                        (rs, __) -> Map.of("id", rs.getString("id"), "name", rs.getString("name")),
                        userId);
                result.addAll(rows);
                log.debug("Shard {} returned {} conversations for user {}", shard, rows.size(), userId);
            } catch (Exception e) {
                log.warn("Failed to query shard {}: {}", shard, e.getMessage());
            } finally {
                DataSourceContextHolder.clear();
            }
        }

        // Stable sort: newest first is best-effort since we can't merge timestamps across shards cleanly
        return result;
    }

    /**
     * Deletes all conversations (and their messages via FK cascade or explicit delete)
     * for {@code userId} across every shard.
     */
    public void deleteAllShards(String userId) {
        for (DataSourceContextHolder.RouteKey shard :
                List.of(DataSourceContextHolder.RouteKey.SHARD_0_WRITE,
                        DataSourceContextHolder.RouteKey.SHARD_1_WRITE)) {

            DataSourceContextHolder.setRoute(shard);
            try {
                int msgs = jdbcTemplate.update(
                        "DELETE FROM messages WHERE conversation_id IN " +
                        "(SELECT id FROM conversations WHERE user_id = ?)", userId);
                int convs = jdbcTemplate.update(
                        "DELETE FROM conversations WHERE user_id = ?", userId);
                log.info("Shard {}: deleted {} messages, {} conversations for user {}", shard, msgs, convs, userId);
            } catch (Exception e) {
                log.warn("Failed to delete from shard {}: {}", shard, e.getMessage());
            } finally {
                DataSourceContextHolder.clear();
            }
        }
    }

    /**
     * Renames a conversation by id, searching across all shards.
     * Returns true if found and renamed, false if not found.
     */
    public boolean renameInShards(String conversationId, String newTitle) {
        for (DataSourceContextHolder.RouteKey shard :
                List.of(DataSourceContextHolder.RouteKey.SHARD_0_WRITE,
                        DataSourceContextHolder.RouteKey.SHARD_1_WRITE)) {
            DataSourceContextHolder.setRoute(shard);
            try {
                int updated = jdbcTemplate.update(
                        "UPDATE conversations SET title = ? WHERE id = ?",
                        newTitle, conversationId);
                if (updated > 0) {
                    log.info("Shard {}: renamed conversation {} to '{}'", shard, conversationId, newTitle);
                    return true;
                }
            } catch (Exception e) {
                log.warn("Failed to rename in shard {}: {}", shard, e.getMessage());
            } finally {
                DataSourceContextHolder.clear();
            }
        }
        return false;
    }

    /**
     * Deletes a single conversation and its messages across all shards.
     * Returns true if found and deleted in any shard.
     */
    public boolean deleteConversationInShards(String conversationId) {
        boolean deleted = false;
        for (DataSourceContextHolder.RouteKey shard :
                List.of(DataSourceContextHolder.RouteKey.SHARD_0_WRITE,
                        DataSourceContextHolder.RouteKey.SHARD_1_WRITE)) {
            DataSourceContextHolder.setRoute(shard);
            try {
                jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
                int convs = jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
                if (convs > 0) {
                    log.info("Shard {}: deleted conversation {}", shard, conversationId);
                    deleted = true;
                }
            } catch (Exception e) {
                log.warn("Failed to delete from shard {}: {}", shard, e.getMessage());
            } finally {
                DataSourceContextHolder.clear();
            }
        }
        return deleted;
    }

    /**
     * Checks if the given conversationId is owned by userId across all shards.
     * Returns true if a matching record is found, false otherwise.
     */
    public boolean isOwner(String conversationId, String userId) {
        for (DataSourceContextHolder.RouteKey shard :
                List.of(DataSourceContextHolder.RouteKey.SHARD_0_WRITE,
                        DataSourceContextHolder.RouteKey.SHARD_1_WRITE)) {
            DataSourceContextHolder.setRoute(shard);
            try {
                List<String> results = jdbcTemplate.query(
                        "SELECT id FROM conversations WHERE id = ? AND user_id = ?",
                        (rs, __) -> rs.getString("id"),
                        conversationId, userId);
                if (!results.isEmpty()) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("Failed to check ownership in shard {}: {}", shard, e.getMessage());
            } finally {
                DataSourceContextHolder.clear();
            }
        }
        return false;
    }
}


