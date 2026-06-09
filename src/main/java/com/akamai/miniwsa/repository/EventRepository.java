package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.EnrichedEvent;

import java.util.List;

/**
 * Abstraction over bulk persistence of enriched security events.
 *
 * TODO(storage-slice): replace with a ClickHouse-backed implementation using the JDBC
 * batch-insert API and the schema described in DESIGN.md "Persistence Layer". The current
 * {@link InMemoryEventRepository} is a temporary placeholder only.
 */
public interface EventRepository {

    void insertBatch(List<EnrichedEvent> events);
}
