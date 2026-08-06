package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockResolutionPending;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Repository contract for stock-name clarification pending state.
 */
public interface StockResolutionPendingRepository {

    String KEY_PREFIX = "trading:stock-resolution:";

    Duration DEFAULT_TTL = Duration.ofMinutes(10);

    Duration DEFAULT_CLAIM_TIMEOUT = Duration.ofSeconds(60);

    Optional<StockResolutionPending> findBySessionId(String sessionId);

    void createOrReplace(String sessionId, StockResolutionPending pending);

    void delete(String sessionId);

    boolean compareAndSet(String sessionId,
                          String expectedVersion,
                          StockResolutionPending newPending,
                          boolean refreshTtl);

    Optional<StockResolutionPending> claim(String sessionId,
                                           String expectedVersion,
                                           String claimId,
                                           Instant now,
                                           Instant claimExpiresAt);

    boolean releaseClaim(String sessionId,
                         String expectedVersion,
                         String claimId,
                         StockResolutionPending pendingToRestore);

    boolean deleteClaimed(String sessionId,
                          String expectedVersion,
                          String claimId);
}
