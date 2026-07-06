package com.llmops.gateway.repository;

import com.llmops.gateway.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    
    /**
     * Find all events that have not yet been successfully published to Kafka.
     * Ordered by creation time to preserve sequencing.
     */
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
