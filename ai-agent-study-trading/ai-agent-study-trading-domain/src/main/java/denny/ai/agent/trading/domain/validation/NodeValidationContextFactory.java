package denny.ai.agent.trading.domain.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.StockIdentityVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import denny.ai.agent.trading.api.vo.TechnicalIndicatorsVO;
import denny.ai.agent.trading.api.vo.TechnicalReportVO;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NodeValidationContextFactory {

    public static final String KNOWN_STOCK_ENTITIES_KEY = "tradingKnownStockEntities";
    private static final Pattern STOCK_CODE = Pattern.compile(
            "(?<![0-9])([0-9]{6})(?:\\.(?:SH|SZ|BJ))?(?![0-9])",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public NodeValidationContextFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public NodeValidationContext create(TradingStateContext stateContext,
                                        String nodeName,
                                        Object nodeValue) {
        Objects.requireNonNull(stateContext, "stateContext");
        AllowedEntitySet.Builder entities = AllowedEntitySet.forTarget(stateContext.getTargetContext());
        registerKnownStocks(stateContext, entities);
        allowTrustedInputCodes(stateContext.getTradingContext(), nodeValue, entities);
        return new NodeValidationContext(stateContext.getTargetContext(), nodeName,
                entities.build(), numericFacts(stateContext.getTradingContext(), nodeValue));
    }

    @SuppressWarnings("unchecked")
    private void registerKnownStocks(TradingStateContext stateContext, AllowedEntitySet.Builder entities) {
        Object known = stateContext.getDynamicContext().getValue(KNOWN_STOCK_ENTITIES_KEY);
        if (known instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof StockIdentityVO identity) {
                    entities.registerKnownStock(identity);
                }
            }
        }
    }

    private void allowTrustedInputCodes(TradingContextVO context,
                                        Object nodeValue,
                                        AllowedEntitySet.Builder entities) {
        List<Object> trustedInputs = new ArrayList<>();
        trustedInputs.add(context.getStockInfo());
        trustedInputs.add(context.getFundamentalReport());
        trustedInputs.add(context.getTechnicalReport());
        trustedInputs.add(context.getSentimentReport());
        trustedInputs.add(context.getNewsReport());
        trustedInputs.add(context.getInvestmentDebate());
        trustedInputs.add(context.getInvestmentPlan());
        trustedInputs.add(context.getRiskDebate());
        if (nodeValue instanceof denny.ai.agent.trading.api.vo.NewsReportVO newsReport) {
            trustedInputs.add(newsReport.getNewsItems());
        }
        for (Object input : trustedInputs) {
            if (input == null) {
                continue;
            }
            try {
                Matcher matcher = STOCK_CODE.matcher(objectMapper.writeValueAsString(input));
                while (matcher.find()) {
                    entities.allowStockCode(matcher.group(1));
                }
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Unable to inspect trusted node input", error);
            }
        }
    }

    private List<NumericInputFact> numericFacts(TradingContextVO context, Object nodeValue) {
        Map<String, NumericInputFact> facts = new LinkedHashMap<>();
        StockInfoVO stockInfo = context.getStockInfo();
        if (stockInfo != null && stockInfo.getCurrentPrice() != null) {
            add(facts, NumericInputFact.exact("currentPrice", stockInfo.getCurrentPrice(),
                    NumericInputFact.Unit.CNY, "currentPrice", "当前价", "当前价格"));
        }

        FundamentalDataVO fundamental = nodeValue instanceof FundamentalReportVO report
                ? report.getRawData()
                : context.getFundamentalReport() == null ? null : context.getFundamentalReport().getRawData();
        if (fundamental != null) {
            addPercentage(facts, "roe", fundamental.getRoe(), "ROE", "净资产收益率");
            addPercentage(facts, "roa", fundamental.getRoa(), "ROA", "总资产收益率");
            addPercentage(facts, "grossMargin", fundamental.getGrossMargin(), "grossMargin", "毛利率");
            addPercentage(facts, "netMargin", fundamental.getNetMargin(), "netMargin", "净利率");
            addPercentage(facts, "revenueGrowth", fundamental.getRevenueGrowth(), "revenueGrowth", "营收增长率");
            addPercentage(facts, "netIncomeGrowth", fundamental.getNetIncomeGrowth(),
                    "netIncomeGrowth", "净利润增长率");
            addPercentage(facts, "debtToAssets", fundamental.getDebtToAssets(),
                    "debtToAssets", "资产负债率");
        }

        TechnicalIndicatorsVO indicators = nodeValue instanceof TechnicalReportVO report
                ? report.getIndicators()
                : context.getTechnicalReport() == null ? null : context.getTechnicalReport().getIndicators();
        if (indicators != null) {
            addRaw(facts, "ma5", indicators.getMa5(), "MA5", "ma5");
            addRaw(facts, "ma10", indicators.getMa10(), "MA10", "ma10");
            addRaw(facts, "ma20", indicators.getMa20(), "MA20", "ma20");
            addRaw(facts, "macd", indicators.getMacd(), "MACD", "macd");
            addRaw(facts, "rsi6", decimal(indicators.getRsi6()), "RSI6", "rsi6");
        }
        return List.copyOf(facts.values());
    }

    private void addPercentage(Map<String, NumericInputFact> facts,
                               String field,
                               Double value,
                               String... labels) {
        if (value != null) {
            add(facts, NumericInputFact.exact(field, decimal(value),
                    NumericInputFact.Unit.PERCENTAGE_POINT, labels));
        }
    }

    private void addRaw(Map<String, NumericInputFact> facts,
                        String field,
                        BigDecimal value,
                        String... labels) {
        if (value != null) {
            add(facts, NumericInputFact.exact(field, value, NumericInputFact.Unit.RAW, labels));
        }
    }

    private void add(Map<String, NumericInputFact> facts, NumericInputFact fact) {
        facts.putIfAbsent(fact.field(), fact);
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
