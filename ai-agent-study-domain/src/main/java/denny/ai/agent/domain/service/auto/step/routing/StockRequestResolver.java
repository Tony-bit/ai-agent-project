package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.model.valobj.stock.StockAnalysisMode;
import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.model.valobj.stock.StockNameResolutionResult;
import denny.ai.agent.domain.model.valobj.stock.StockNameResolutionStatus;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRouteDecisionType;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRoutingDecision;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPending;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPendingStatus;
import denny.ai.agent.domain.model.valobj.stock.StockTargetStatus;
import denny.ai.agent.domain.service.stock.StockNameIndexHolder;
import denny.ai.agent.domain.service.stock.StockNameResolutionService;
import denny.ai.agent.domain.service.stock.StockResolutionPendingRepository;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stock request state machine used before executor selection.
 */
public class StockRequestResolver {

    static final String TARGET_CLARIFICATION_PROMPT = "请提供股票名称或六位代码。";
    static final String ANALYSIS_MODE_CLARIFICATION_PROMPT = AnalysisDepthFollowUpResolver.CLARIFICATION_PROMPT;
    private static final Pattern STOCK_CODE_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern INDEX_PATTERN = Pattern.compile("^第?\\s*([1-9]\\d*)\\s*个?$");

    private final StockNameIndexHolder indexHolder;
    private final StockNameResolutionService nameResolutionService;
    private final StockResolutionPendingRepository pendingRepository;
    private final AnalysisDepthFollowUpResolver analysisDepthFollowUpResolver;
    private final Clock clock;

    public StockRequestResolver(StockNameIndexHolder indexHolder,
                                StockNameResolutionService nameResolutionService,
                                StockResolutionPendingRepository pendingRepository,
                                AnalysisDepthFollowUpResolver analysisDepthFollowUpResolver,
                                Clock clock) {
        this.indexHolder = Objects.requireNonNull(indexHolder, "indexHolder");
        this.nameResolutionService = Objects.requireNonNull(nameResolutionService, "nameResolutionService");
        this.pendingRepository = Objects.requireNonNull(pendingRepository, "pendingRepository");
        this.analysisDepthFollowUpResolver = Objects.requireNonNull(
                analysisDepthFollowUpResolver, "analysisDepthFollowUpResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StockRequestRoutingDecision resolve(String sessionId,
                                               String currentMessage,
                                               IntentTypeEnum intent,
                                               StockSlot extractedStockSlot) {
        String normalizedMessage = currentMessage == null ? "" : currentMessage.trim();
        Optional<StockResolutionPending> pendingOptional = pendingRepository.findBySessionId(sessionId);
        if (pendingOptional.isPresent()) {
            return resolveWithPending(sessionId, normalizedMessage, intent, extractedStockSlot, pendingOptional.get());
        }
        return resolveFreshRequest(sessionId, normalizedMessage, intent, extractedStockSlot);
    }

    private StockRequestRoutingDecision resolveFreshRequest(String sessionId,
                                                            String currentMessage,
                                                            IntentTypeEnum intent,
                                                            StockSlot extractedStockSlot) {
        StockSlot slot = normalizeSlot(extractedStockSlot);
        StockAnalysisMode analysisMode = StockAnalysisMode.fromCode(slot.getAnalysisMode());
        String explicitCode = normalizeCode(slot.getStockCode());
        String stockNameQuery = normalizeNameQuery(slot.getStockNameQuery());

        if (StringUtils.hasText(explicitCode)) {
            return routeResolved(sessionId, currentMessage, currentMessage, null, explicitCode, analysisMode);
        }

        if (!StringUtils.hasText(stockNameQuery)) {
            if (!looksLikeStockIntent(intent, slot)) {
                return noDecision();
            }
            StockResolutionPending pending = unresolvedPending(currentMessage, analysisMode, clock.instant());
            pendingRepository.createOrReplace(sessionId, pending);
            return clarifyTarget(pending, TARGET_CLARIFICATION_PROMPT);
        }

        StockNameResolutionResult resolution = resolveAgainstReadyIndex(stockNameQuery);
        return handleResolutionWithoutPending(sessionId, currentMessage, analysisMode, resolution);
    }

    private StockRequestRoutingDecision resolveWithPending(String sessionId,
                                                           String currentMessage,
                                                           IntentTypeEnum intent,
                                                           StockSlot extractedStockSlot,
                                                           StockResolutionPending pending) {
        StockSlot slot = normalizeSlot(extractedStockSlot);
        String explicitCode = normalizeCode(slot.getStockCode());
        String stockNameQuery = normalizeNameQuery(slot.getStockNameQuery());
        StockAnalysisMode explicitMode = StockAnalysisMode.fromCode(slot.getAnalysisMode());
        Instant now = clock.instant();

        Selection selection = resolvePendingSelection(currentMessage, explicitCode, pending);
        if (selection != null) {
            return handleResolvedSelection(sessionId, pending, selection.record(), now);
        }

        if (explicitMode != StockAnalysisMode.UNRESOLVED && pending.getTargetStatus() == StockTargetStatus.RESOLVED) {
            return handleModeSelection(sessionId, pending, explicitMode, now);
        }

        AnalysisDepthFollowUpResolver.Choice choice = analysisDepthFollowUpResolver.resolveChoice(currentMessage);
        if (choice != null && pending.getTargetStatus() == StockTargetStatus.RESOLVED) {
            return handleModeSelection(sessionId, pending,
                    choice == AnalysisDepthFollowUpResolver.Choice.FULL
                            ? StockAnalysisMode.FULL : StockAnalysisMode.QUICK,
                    now);
        }

        if (StringUtils.hasText(explicitCode)) {
            return handlePendingReplacementByCode(sessionId, pending, explicitCode, now);
        }

        if (StringUtils.hasText(stockNameQuery) && !matchesCurrentPending(pending, stockNameQuery)) {
            StockNameResolutionResult resolution = resolveAgainstReadyIndex(stockNameQuery);
            return handleResolutionReplacingPending(sessionId, pending, stockNameQuery, resolution, now);
        }

        if (!looksLikeStockIntent(intent, slot)) {
            pendingRepository.delete(sessionId);
            return noDecision();
        }

        return reClarifyPending(pending);
    }

    private StockRequestRoutingDecision handleResolutionWithoutPending(String sessionId,
                                                                       String currentMessage,
                                                                       StockAnalysisMode analysisMode,
                                                                       StockNameResolutionResult resolution) {
        Instant now = clock.instant();
        return switch (resolution.getStatus()) {
            case NOT_FOUND -> StockRequestRoutingDecision.builder()
                    .decisionType(StockRequestRouteDecisionType.NOT_FOUND)
                    .stockTargetStatus(StockTargetStatus.UNRESOLVED)
                    .analysisMode(analysisMode)
                    .clarificationPrompt(defaultMessage(resolution.getMessage(), "股票不存在，请检查股票名称或输入六位代码。"))
                    .message(resolution.getMessage())
                    .build();
            case AMBIGUOUS -> {
                StockResolutionPending pending = ambiguousPending(
                        currentMessage, resolution.getStockNameQuery(), analysisMode, resolution.getCandidates(), now);
                pendingRepository.createOrReplace(sessionId, pending);
                yield clarifyTarget(pending, buildCandidatePrompt(resolution.getStockNameQuery(), resolution.getCandidates()));
            }
            case TOO_MANY_CANDIDATES -> StockRequestRoutingDecision.builder()
                    .decisionType(StockRequestRouteDecisionType.CLARIFY_TARGET)
                    .stockTargetStatus(StockTargetStatus.UNRESOLVED)
                    .analysisMode(analysisMode)
                    .clarificationPrompt(defaultMessage(resolution.getMessage(), "候选股票过多，请输入更完整的名称。"))
                    .message(resolution.getMessage())
                    .build();
            case RESOLVED -> handleResolvedRecord(
                    sessionId, currentMessage, currentMessage, resolution.getResolvedRecord(), analysisMode, now);
            case ERROR -> errorDecision(defaultMessage(resolution.getMessage(), "股票解析暂时不可用，请稍后重试。"));
        };
    }

    private StockRequestRoutingDecision handleResolutionReplacingPending(String sessionId,
                                                                        StockResolutionPending pending,
                                                                        String stockNameQuery,
                                                                        StockNameResolutionResult resolution,
                                                                        Instant now) {
        return switch (resolution.getStatus()) {
            case NOT_FOUND -> {
                pendingRepository.delete(sessionId);
                yield StockRequestRoutingDecision.builder()
                        .decisionType(StockRequestRouteDecisionType.NOT_FOUND)
                        .stockTargetStatus(StockTargetStatus.UNRESOLVED)
                        .analysisMode(pending.getAnalysisMode())
                        .clarificationPrompt(defaultMessage(resolution.getMessage(), "股票不存在，请检查股票名称或输入六位代码。"))
                        .message(resolution.getMessage())
                        .build();
            }
            case AMBIGUOUS -> {
                StockResolutionPending replacement = ambiguousPending(
                        pending.getOriginalQuery(), stockNameQuery, pending.getAnalysisMode(),
                        resolution.getCandidates(), now);
                pendingRepository.compareAndSet(sessionId, pending.getVersion(), replacement, true);
                yield clarifyTarget(replacement, buildCandidatePrompt(stockNameQuery, resolution.getCandidates()));
            }
            case TOO_MANY_CANDIDATES -> StockRequestRoutingDecision.builder()
                    .decisionType(StockRequestRouteDecisionType.CLARIFY_TARGET)
                    .stockTargetStatus(StockTargetStatus.UNRESOLVED)
                    .analysisMode(pending.getAnalysisMode())
                    .clarificationPrompt(defaultMessage(resolution.getMessage(), "候选股票过多，请输入更完整的名称。"))
                    .message(resolution.getMessage())
                    .build();
            case RESOLVED -> handleResolvedRecord(
                    sessionId, pending.getOriginalQuery(), pending.getOriginalQuery(),
                    resolution.getResolvedRecord(), pending.getAnalysisMode(), now, pending.getVersion());
            case ERROR -> errorDecision(defaultMessage(resolution.getMessage(), "股票解析暂时不可用，请稍后重试。"));
        };
    }

    private StockRequestRoutingDecision handleResolvedSelection(String sessionId,
                                                                StockResolutionPending pending,
                                                                StockNameRecord record,
                                                                Instant now) {
        return handleResolvedRecord(
                sessionId,
                pending.getOriginalQuery(),
                pending.getOriginalQuery(),
                record,
                pending.getAnalysisMode(),
                now,
                pending.getVersion());
    }

    private StockRequestRoutingDecision handleResolvedRecord(String sessionId,
                                                             String originalQuery,
                                                             String executionQuerySource,
                                                             StockNameRecord record,
                                                             StockAnalysisMode analysisMode,
                                                             Instant now) {
        return handleResolvedRecord(sessionId, originalQuery, executionQuerySource, record, analysisMode, now, null);
    }

    private StockRequestRoutingDecision handleResolvedRecord(String sessionId,
                                                             String originalQuery,
                                                             String executionQuerySource,
                                                             StockNameRecord record,
                                                             StockAnalysisMode analysisMode,
                                                             Instant now,
                                                             String expectedVersion) {
        if (analysisMode == StockAnalysisMode.UNRESOLVED) {
            StockResolutionPending pending = resolvedPending(originalQuery, record, analysisMode, now);
            if (expectedVersion == null) {
                pendingRepository.createOrReplace(sessionId, pending);
            } else {
                if (!pendingRepository.compareAndSet(sessionId, expectedVersion, pending, true)) {
                    return errorDecision("股票状态已更新，请重试。");
                }
            }
            return clarifyAnalysisMode(pending);
        }
        if (expectedVersion != null) {
            StockResolutionPending pending = resolvedPending(originalQuery, record, analysisMode, now);
            if (!pendingRepository.compareAndSet(sessionId, expectedVersion, pending, true)) {
                return errorDecision("股票状态已更新，请重试。");
            }
            return routeResolved(sessionId, originalQuery, executionQuerySource,
                    record.getStockName(), record.getStockCode(), analysisMode, pending.getVersion());
        }
        return routeResolved(sessionId, originalQuery, executionQuerySource,
                record.getStockName(), record.getStockCode(), analysisMode, null);
    }

    private StockRequestRoutingDecision handleModeSelection(String sessionId,
                                                            StockResolutionPending pending,
                                                            StockAnalysisMode selectedMode,
                                                            Instant now) {
        StockResolutionPending updated = StockResolutionPending.builder()
                .version(newVersion())
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery(pending.getOriginalQuery())
                .stockNameQuery(pending.getStockNameQuery())
                .targetStatus(pending.getTargetStatus())
                .orderedCandidates(pending.getOrderedCandidates())
                .resolvedStockName(pending.getResolvedStockName())
                .resolvedStockCode(pending.getResolvedStockCode())
                .analysisMode(selectedMode)
                .createdAt(pending.getCreatedAt())
                .expiresAt(now.plus(StockResolutionPendingRepository.DEFAULT_TTL))
                .build();
        if (selectedMode == StockAnalysisMode.UNRESOLVED) {
            return clarifyAnalysisMode(updated);
        }
        if (!pendingRepository.compareAndSet(sessionId, pending.getVersion(), updated, true)) {
            return errorDecision("股票状态已更新，请重试。");
        }
        return routeResolved(sessionId, pending.getOriginalQuery(), pending.getOriginalQuery(),
                updated.getResolvedStockName(), updated.getResolvedStockCode(), selectedMode, updated.getVersion());
    }

    private StockRequestRoutingDecision handlePendingReplacementByCode(String sessionId,
                                                                       StockResolutionPending pending,
                                                                       String explicitCode,
                                                                       Instant now) {
        if (pending.getTargetStatus() == StockTargetStatus.AMBIGUOUS) {
            StockNameRecord matching = pending.getOrderedCandidates().stream()
                    .filter(candidate -> explicitCode.equals(candidate.getStockCode()))
                    .findFirst()
                    .orElse(null);
            if (matching != null) {
                return handleResolvedSelection(sessionId, pending, matching, now);
            }
        }
        return routeResolved(sessionId, pending.getOriginalQuery(), pending.getOriginalQuery(),
                null, explicitCode, pending.getAnalysisMode(), pending.getVersion());
    }

    private StockRequestRoutingDecision routeResolved(String sessionId,
                                                      String originalQuery,
                                                      String executionQuerySource,
                                                      String stockName,
                                                      String stockCode,
                                                      StockAnalysisMode analysisMode) {
        return routeResolved(sessionId, originalQuery, executionQuerySource, stockName, stockCode, analysisMode, null);
    }

    private StockRequestRoutingDecision routeResolved(String sessionId,
                                                      String originalQuery,
                                                      String executionQuerySource,
                                                      String stockName,
                                                      String stockCode,
                                                      StockAnalysisMode analysisMode,
                                                      String expectedVersion) {
        String claimId = null;
        String pendingVersion = expectedVersion;
        if (expectedVersion != null) {
            claimId = UUID.randomUUID().toString();
            Optional<StockResolutionPending> claimed = pendingRepository.claim(
                    sessionId,
                    expectedVersion,
                    claimId,
                    clock.instant(),
                    clock.instant().plus(StockResolutionPendingRepository.DEFAULT_CLAIM_TIMEOUT));
            if (claimed.isEmpty()) {
                return errorDecision("股票状态已更新，请重试。");
            }
            pendingVersion = claimed.get().getVersion();
        }
        if (analysisMode == StockAnalysisMode.QUICK) {
            return StockRequestRoutingDecision.builder()
                    .decisionType(StockRequestRouteDecisionType.ROUTE_GENERAL_CHAT)
                    .stockTargetStatus(StockTargetStatus.RESOLVED)
                    .analysisMode(analysisMode)
                    .stockSlot(resolvedSlot(stockName, stockCode, analysisMode))
                    .executionQuery(buildQuickExecutionQuery(executionQuerySource, stockName, stockCode))
                    .pendingVersion(pendingVersion)
                    .claimId(claimId)
                    .build();
        }
        return StockRequestRoutingDecision.builder()
                .decisionType(StockRequestRouteDecisionType.ROUTE_TRADING)
                .stockTargetStatus(StockTargetStatus.RESOLVED)
                .analysisMode(analysisMode)
                .stockSlot(resolvedSlot(stockName, stockCode, analysisMode))
                .pendingVersion(pendingVersion)
                .claimId(claimId)
                .build();
    }

    private StockRequestRoutingDecision reClarifyPending(StockResolutionPending pending) {
        if (pending.getTargetStatus() == StockTargetStatus.AMBIGUOUS) {
            return clarifyTarget(pending, buildCandidatePrompt(pending.getStockNameQuery(), pending.getOrderedCandidates()));
        }
        if (pending.getTargetStatus() == StockTargetStatus.RESOLVED
                && pending.getAnalysisMode() == StockAnalysisMode.UNRESOLVED) {
            return clarifyAnalysisMode(pending);
        }
        return clarifyTarget(pending, TARGET_CLARIFICATION_PROMPT);
    }

    private StockRequestRoutingDecision clarifyTarget(StockResolutionPending pending, String prompt) {
        return StockRequestRoutingDecision.builder()
                .decisionType(StockRequestRouteDecisionType.CLARIFY_TARGET)
                .stockTargetStatus(pending.getTargetStatus())
                .analysisMode(pending.getAnalysisMode())
                .stockSlot(StockSlot.builder()
                        .stockNameQuery(pending.getStockNameQuery())
                        .stockName(pending.getResolvedStockName())
                        .stockCode(pending.getResolvedStockCode())
                        .analysisMode(pending.getAnalysisMode().name())
                        .build())
                .clarificationPrompt(prompt)
                .build();
    }

    private StockRequestRoutingDecision clarifyAnalysisMode(StockResolutionPending pending) {
        return StockRequestRoutingDecision.builder()
                .decisionType(StockRequestRouteDecisionType.CLARIFY_ANALYSIS_MODE)
                .stockTargetStatus(StockTargetStatus.RESOLVED)
                .analysisMode(StockAnalysisMode.UNRESOLVED)
                .stockSlot(StockSlot.builder()
                        .stockName(pending.getResolvedStockName())
                        .stockCode(pending.getResolvedStockCode())
                        .analysisMode(StockAnalysisMode.UNRESOLVED.name())
                        .build())
                .clarificationPrompt(ANALYSIS_MODE_CLARIFICATION_PROMPT)
                .build();
    }

    private StockRequestRoutingDecision errorDecision(String message) {
        return StockRequestRoutingDecision.builder()
                .decisionType(StockRequestRouteDecisionType.ERROR)
                .message(message)
                .clarificationPrompt(message)
                .build();
    }

    private StockRequestRoutingDecision noDecision() {
        return StockRequestRoutingDecision.builder().build();
    }

    private StockNameResolutionResult resolveAgainstReadyIndex(String stockNameQuery) {
        Optional<StockNameIndex> readyIndex = indexHolder.readyIndex();
        if (readyIndex.isEmpty()) {
            return StockNameResolutionResult.builder()
                    .status(StockNameResolutionStatus.ERROR)
                    .stockNameQuery(stockNameQuery)
                    .message("股票名称索引暂时不可用，请稍后重试。")
                    .build();
        }
        return nameResolutionService.resolve(readyIndex.get(), stockNameQuery);
    }

    private boolean looksLikeStockIntent(IntentTypeEnum intent, StockSlot slot) {
        return intent == IntentTypeEnum.STOCK_ANALYSIS
                || StringUtils.hasText(slot.getStockNameQuery())
                || StringUtils.hasText(slot.getStockCode())
                || StringUtils.hasText(slot.getAnalysisMode());
    }

    private boolean matchesCurrentPending(StockResolutionPending pending, String stockNameQuery) {
        String normalized = StockNameIndex.normalize(stockNameQuery);
        if (normalized.isEmpty()) {
            return false;
        }
        if (StockNameIndex.normalize(pending.getStockNameQuery()).equals(normalized)) {
            return true;
        }
        if (StockNameIndex.normalize(pending.getResolvedStockName()).equals(normalized)) {
            return true;
        }
        return pending.getOrderedCandidates().stream()
                .map(StockNameRecord::getStockName)
                .map(StockNameIndex::normalize)
                .anyMatch(normalized::equals);
    }

    private Selection resolvePendingSelection(String currentMessage,
                                              String explicitCode,
                                              StockResolutionPending pending) {
        if (pending.getTargetStatus() != StockTargetStatus.AMBIGUOUS) {
            return null;
        }
        if (StringUtils.hasText(explicitCode)) {
            return pending.getOrderedCandidates().stream()
                    .filter(candidate -> explicitCode.equals(candidate.getStockCode()))
                    .findFirst()
                    .map(Selection::new)
                    .orElse(null);
        }
        Integer index = parseCandidateIndex(currentMessage);
        if (index != null && index >= 1 && index <= pending.getOrderedCandidates().size()) {
            return new Selection(pending.getOrderedCandidates().get(index - 1));
        }
        String normalizedMessage = StockNameIndex.normalize(currentMessage);
        if (!normalizedMessage.isEmpty()) {
            return pending.getOrderedCandidates().stream()
                    .filter(candidate -> normalizedMessage.equals(StockNameIndex.normalize(candidate.getStockName())))
                    .findFirst()
                    .map(Selection::new)
                    .orElse(null);
        }
        return null;
    }

    private Integer parseCandidateIndex(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String trimmed = message.trim();
        Matcher matcher = INDEX_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return switch (trimmed) {
            case "第一个" -> 1;
            case "第二个" -> 2;
            case "第三个" -> 3;
            case "第四个" -> 4;
            case "第五个" -> 5;
            default -> null;
        };
    }

    private String normalizeCode(String stockCode) {
        if (!StringUtils.hasText(stockCode)) {
            return null;
        }
        Matcher matcher = STOCK_CODE_PATTERN.matcher(stockCode.trim().toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalizeNameQuery(String stockNameQuery) {
        return StringUtils.hasText(stockNameQuery) ? stockNameQuery.trim() : null;
    }

    private StockSlot normalizeSlot(StockSlot extractedStockSlot) {
        return extractedStockSlot == null ? new StockSlot() : extractedStockSlot;
    }

    private StockSlot resolvedSlot(String stockName, String stockCode, StockAnalysisMode analysisMode) {
        return StockSlot.builder()
                .stockName(stockName)
                .stockCode(stockCode)
                .analysisMode(analysisMode.name())
                .build();
    }

    private String buildQuickExecutionQuery(String originalQuery, String stockName, String stockCode) {
        return """
                用户原始问题：
                %s

                系统已确认的股票：
                %s（%s）

                请基于上述已确认股票回答原始问题，不要重新识别股票。
                """.formatted(
                defaultMessage(originalQuery, ""),
                defaultMessage(stockName, "该股票"),
                defaultMessage(stockCode, "未知代码"));
    }

    private StockResolutionPending unresolvedPending(String originalQuery,
                                                     StockAnalysisMode analysisMode,
                                                     Instant now) {
        return StockResolutionPending.builder()
                .version(newVersion())
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery(originalQuery)
                .targetStatus(StockTargetStatus.UNRESOLVED)
                .analysisMode(analysisMode)
                .createdAt(now)
                .expiresAt(now.plus(StockResolutionPendingRepository.DEFAULT_TTL))
                .build();
    }

    private StockResolutionPending ambiguousPending(String originalQuery,
                                                    String stockNameQuery,
                                                    StockAnalysisMode analysisMode,
                                                    List<StockNameRecord> candidates,
                                                    Instant now) {
        return StockResolutionPending.builder()
                .version(newVersion())
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery(originalQuery)
                .stockNameQuery(stockNameQuery)
                .targetStatus(StockTargetStatus.AMBIGUOUS)
                .orderedCandidates(List.copyOf(candidates))
                .analysisMode(analysisMode)
                .createdAt(now)
                .expiresAt(now.plus(StockResolutionPendingRepository.DEFAULT_TTL))
                .build();
    }

    private StockResolutionPending resolvedPending(String originalQuery,
                                                   StockNameRecord record,
                                                   StockAnalysisMode analysisMode,
                                                   Instant now) {
        return StockResolutionPending.builder()
                .version(newVersion())
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery(originalQuery)
                .stockNameQuery(record.getStockName())
                .targetStatus(StockTargetStatus.RESOLVED)
                .resolvedStockName(record.getStockName())
                .resolvedStockCode(record.getStockCode())
                .analysisMode(analysisMode)
                .createdAt(now)
                .expiresAt(now.plus(StockResolutionPendingRepository.DEFAULT_TTL))
                .build();
    }

    private String buildCandidatePrompt(String stockNameQuery, List<StockNameRecord> candidates) {
        StringBuilder builder = new StringBuilder("找到多只名称包含“")
                .append(stockNameQuery)
                .append("”的股票：");
        for (int i = 0; i < candidates.size(); i++) {
            StockNameRecord candidate = candidates.get(i);
            builder.append("\n")
                    .append(i + 1)
                    .append(". ")
                    .append(candidate.getStockName())
                    .append("（")
                    .append(candidate.getStockCode())
                    .append("）");
        }
        builder.append("\n请回复序号、完整候选名称或候选代码；也可以输入其他股票名称或六位代码切换股票。");
        return builder.toString();
    }

    private String defaultMessage(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String newVersion() {
        return UUID.randomUUID().toString();
    }

    private record Selection(StockNameRecord record) {
    }
}
