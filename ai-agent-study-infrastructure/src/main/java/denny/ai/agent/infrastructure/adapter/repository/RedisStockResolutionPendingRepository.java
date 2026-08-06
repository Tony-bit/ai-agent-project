package denny.ai.agent.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPending;
import denny.ai.agent.domain.service.stock.StockNameMetrics;
import denny.ai.agent.domain.service.stock.StockResolutionPendingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RedisStockResolutionPendingRepository implements StockResolutionPendingRepository {

    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 0
            end
            local decoded = cjson.decode(current)
            if decoded.version ~= ARGV[1] then
              return 0
            end
            local ttlMillis = tonumber(ARGV[4])
            if ARGV[3] == '0' then
              local remaining = redis.call('PTTL', KEYS[1])
              if remaining and remaining > 0 then
                ttlMillis = remaining
              end
            end
            redis.call('PSETEX', KEYS[1], ttlMillis, ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              return nil
            end
            local decoded = cjson.decode(current)
            if decoded.version ~= ARGV[1] then
              return nil
            end
            local currentStatus = decoded.status
            local currentClaimExpiry = tonumber(decoded.claimExpiresAtEpochMillis or '-1')
            local nowMillis = tonumber(ARGV[3])
            if currentStatus ~= 'PENDING' and not (currentStatus == 'CLAIMED' and currentClaimExpiry >= 0 and currentClaimExpiry <= nowMillis) then
              return nil
            end
            decoded.status = 'CLAIMED'
            decoded.claimId = ARGV[2]
            decoded.claimExpiresAt = ARGV[4]
            decoded.claimExpiresAtEpochMillis = tonumber(ARGV[5])
            local encoded = cjson.encode(decoded)
            redis.call('PSETEX', KEYS[1], tonumber(ARGV[6]), encoded)
            return encoded
            """, String.class);

    private static final DefaultRedisScript<Long> RELEASE_CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 0
            end
            local decoded = cjson.decode(current)
            if decoded.version ~= ARGV[1] then
              return 0
            end
            if decoded.claimId ~= ARGV[2] then
              return 0
            end
            redis.call('PSETEX', KEYS[1], tonumber(ARGV[4]), ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> DELETE_CLAIMED_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 0
            end
            local decoded = cjson.decode(current)
            if decoded.version ~= ARGV[1] then
              return 0
            end
            if decoded.claimId ~= ARGV[2] then
              return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final StockNameMetrics stockNameMetrics;

    public RedisStockResolutionPendingRepository(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, new StockNameMetrics());
    }

    @Autowired
    public RedisStockResolutionPendingRepository(StringRedisTemplate stringRedisTemplate,
                                                 StockNameMetrics stockNameMetrics) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.stockNameMetrics = stockNameMetrics;
    }

    @Override
    public Optional<StockResolutionPending> findBySessionId(String sessionId) {
        String json = stringRedisTemplate.opsForValue().get(key(sessionId));
        if (json == null || json.isBlank()) {
            stockNameMetrics.recordPendingLookup(false);
            return Optional.empty();
        }
        StockResolutionPending pending = deserialize(json);
        stockNameMetrics.recordPendingLookup(true);
        log.debug("Stock resolution pending hit: sessionId={}, status={}, version={}",
                sessionId, pending.getStatus(), pending.getVersion());
        return Optional.of(pending);
    }

    @Override
    public void createOrReplace(String sessionId, StockResolutionPending pending) {
        stringRedisTemplate.opsForValue().set(
                key(sessionId),
                serialize(pending),
                DEFAULT_TTL.toMillis(),
                TimeUnit.MILLISECONDS);
        stockNameMetrics.recordPendingLifecycle("create_or_replace", true);
        log.info("Stock resolution pending stored: sessionId={}, status={}, targetStatus={}, candidates={}, version={}",
                sessionId,
                pending.getStatus(),
                pending.getTargetStatus(),
                pending.getOrderedCandidates() == null ? 0 : pending.getOrderedCandidates().size(),
                pending.getVersion());
    }

    @Override
    public void delete(String sessionId) {
        Boolean deleted = stringRedisTemplate.delete(key(sessionId));
        boolean success = Boolean.TRUE.equals(deleted);
        stockNameMetrics.recordPendingLifecycle("delete", success);
        log.info("Stock resolution pending deleted: sessionId={}, deleted={}", sessionId, success);
    }

    @Override
    public boolean compareAndSet(String sessionId,
                                 String expectedVersion,
                                 StockResolutionPending newPending,
                                 boolean refreshTtl) {
        Long updated = stringRedisTemplate.execute(
                COMPARE_AND_SET_SCRIPT,
                List.of(key(sessionId)),
                expectedVersion,
                serialize(newPending),
                refreshTtl ? "1" : "0",
                Long.toString(DEFAULT_TTL.toMillis()));
        boolean success = Long.valueOf(1L).equals(updated);
        stockNameMetrics.recordPendingLifecycle("compare_and_set", success);
        log.info("Stock resolution pending compareAndSet: sessionId={}, expectedVersion={}, success={}, status={}",
                sessionId, expectedVersion, success, newPending.getStatus());
        return success;
    }

    @Override
    public Optional<StockResolutionPending> claim(String sessionId,
                                                  String expectedVersion,
                                                  String claimId,
                                                  Instant now,
                                                  Instant claimExpiresAt) {
        String claimedJson = stringRedisTemplate.execute(
                CLAIM_SCRIPT,
                List.of(key(sessionId)),
                expectedVersion,
                claimId,
                Long.toString(now.toEpochMilli()),
                claimExpiresAt.toString(),
                Long.toString(claimExpiresAt.toEpochMilli()),
                Long.toString(DEFAULT_TTL.toMillis()));
        if (claimedJson == null || claimedJson.isBlank()) {
            stockNameMetrics.recordPendingLifecycle("claim", false);
            return Optional.empty();
        }
        StockResolutionPending pending = deserialize(claimedJson);
        stockNameMetrics.recordPendingLifecycle("claim", true);
        log.info("Stock resolution pending claimed: sessionId={}, version={}, claimId={}, claimExpiresAt={}",
                sessionId, pending.getVersion(), pending.getClaimId(), pending.getClaimExpiresAt());
        return Optional.of(pending);
    }

    @Override
    public boolean releaseClaim(String sessionId,
                                String expectedVersion,
                                String claimId,
                                StockResolutionPending pendingToRestore) {
        Long restored = stringRedisTemplate.execute(
                RELEASE_CLAIM_SCRIPT,
                List.of(key(sessionId)),
                expectedVersion,
                claimId,
                serialize(pendingToRestore),
                Long.toString(DEFAULT_TTL.toMillis()));
        boolean success = Long.valueOf(1L).equals(restored);
        stockNameMetrics.recordPendingLifecycle("release_claim", success);
        log.info("Stock resolution pending claim released: sessionId={}, expectedVersion={}, success={}",
                sessionId, expectedVersion, success);
        return success;
    }

    @Override
    public boolean deleteClaimed(String sessionId,
                                 String expectedVersion,
                                 String claimId) {
        Long deleted = stringRedisTemplate.execute(
                DELETE_CLAIMED_SCRIPT,
                List.of(key(sessionId)),
                expectedVersion,
                claimId);
        boolean success = Long.valueOf(1L).equals(deleted);
        stockNameMetrics.recordPendingLifecycle("delete_claimed", success);
        log.info("Stock resolution claimed pending cleanup: sessionId={}, expectedVersion={}, success={}",
                sessionId, expectedVersion, success);
        return success;
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private String serialize(StockResolutionPending pending) {
        PendingRedisDocument document = PendingRedisDocument.from(pending);
        return JSON.toJSONString(document);
    }

    private StockResolutionPending deserialize(String json) {
        PendingRedisDocument document = JSON.parseObject(json, PendingRedisDocument.class);
        return document.toPending();
    }

    private record PendingRedisDocument(String version,
                                        String status,
                                        String claimId,
                                        String claimExpiresAt,
                                        Long claimExpiresAtEpochMillis,
                                        String originalQuery,
                                        String stockNameQuery,
                                        String targetStatus,
                                        List<denny.ai.agent.domain.model.valobj.stock.StockNameRecord> orderedCandidates,
                                        String resolvedStockName,
                                        String resolvedStockCode,
                                        String analysisMode,
                                        String createdAt,
                                        String expiresAt) {

        static PendingRedisDocument from(StockResolutionPending pending) {
            return new PendingRedisDocument(
                    pending.getVersion(),
                    pending.getStatus() != null ? pending.getStatus().name() : null,
                    pending.getClaimId(),
                    pending.getClaimExpiresAt() != null ? pending.getClaimExpiresAt().toString() : null,
                    pending.getClaimExpiresAt() != null ? pending.getClaimExpiresAt().toEpochMilli() : null,
                    pending.getOriginalQuery(),
                    pending.getStockNameQuery(),
                    pending.getTargetStatus() != null ? pending.getTargetStatus().name() : null,
                    pending.getOrderedCandidates(),
                    pending.getResolvedStockName(),
                    pending.getResolvedStockCode(),
                    pending.getAnalysisMode() != null ? pending.getAnalysisMode().name() : null,
                    pending.getCreatedAt() != null ? pending.getCreatedAt().toString() : null,
                    pending.getExpiresAt() != null ? pending.getExpiresAt().toString() : null);
        }

        StockResolutionPending toPending() {
            return StockResolutionPending.builder()
                    .version(version)
                    .status(status != null ? denny.ai.agent.domain.model.valobj.stock.StockResolutionPendingStatus.valueOf(status) : null)
                    .claimId(claimId)
                    .claimExpiresAt(claimExpiresAt != null ? Instant.parse(claimExpiresAt) : null)
                    .originalQuery(originalQuery)
                    .stockNameQuery(stockNameQuery)
                    .targetStatus(targetStatus != null ? denny.ai.agent.domain.model.valobj.stock.StockTargetStatus.valueOf(targetStatus) : null)
                    .orderedCandidates(orderedCandidates != null ? List.copyOf(orderedCandidates) : List.of())
                    .resolvedStockName(resolvedStockName)
                    .resolvedStockCode(resolvedStockCode)
                    .analysisMode(analysisMode != null ? denny.ai.agent.domain.model.valobj.stock.StockAnalysisMode.valueOf(analysisMode) : null)
                    .createdAt(createdAt != null ? Instant.parse(createdAt) : null)
                    .expiresAt(expiresAt != null ? Instant.parse(expiresAt) : null)
                    .build();
        }
    }
}
