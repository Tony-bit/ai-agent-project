package denny.ai.agent.trading.infra.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.context.TradingTargetContextKeys;
import denny.ai.agent.trading.api.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import denny.ai.agent.trading.api.metrics.TradingRolloutMonitor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;



/**
 * Trading 领域 ToolCallback 工厂类。
 * 将 {@link IStockDataProvider} 的 6 个方法逐个包装为独立的 {@link ToolCallback} 实例。
 * 生成的 ToolCallback Bean 由 {@link TradingToolCallbackProvider} 注册，
 * 再由 {@link denny.ai.agent.domain.service.armory.AiClientNode} 注入到 ChatClient。
 */
@Slf4j
public class TradingToolCallbacks {

    private final IStockDataProvider provider;
    private final ObjectMapper objectMapper;
    private final TradingRolloutMonitor rolloutMonitor;

    public TradingToolCallbacks(IStockDataProvider provider) {
        this(provider, new TradingRolloutMonitor());
    }

    public TradingToolCallbacks(IStockDataProvider provider, TradingRolloutMonitor rolloutMonitor) {
        this.provider = provider;
        this.rolloutMonitor = rolloutMonitor;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public ToolCallback getStockInfoCallback() {
        return new AbstractToolCallback("get_stock_info",
                "获取A股股票的实时行情信息，包括当前价格、52周高低、日成交量、市盈率、市净率等。适用场景：需要查询股票当前价格、涨跌幅、市值等基本信息时调用。注意：仅支持A股股票代码（6位数字，如000001、600000）。",
                buildInputSchema()) {
            @Override
            protected String doExecute(Map<String, Object> input, ToolContext toolContext) {
                TargetContext target = requireTarget(toolContext);
                StockInfoVO vo = provider.getStockInfo(effectiveTicker(input, target));
                return formatStockInfo(vo);
            }
        };
    }

    public ToolCallback getHistoricalBarsCallback() {
        return new AbstractToolCallback("get_historical_bars",
                "获取A股股票的历史K线数据（OHLCV），包含每日开盘价、最高价、最低价、收盘价、成交量、成交额、涨跌额、涨跌幅。适用场景：需要分析股票历史走势、价格波动、成交活跃度、技术分析时调用。",
                buildInputSchema(
                        "startDate", "开始日期，格式 yyyy-MM-dd，如 2024-01-01",
                        "endDate", "结束日期，格式 yyyy-MM-dd，如 2024-12-31")) {
            @Override
            protected String doExecute(Map<String, Object> input, ToolContext toolContext) {
                TargetContext target = requireTarget(toolContext);
                List<OHLCVBarVO> bars = provider.getHistoricalBars(
                        effectiveTicker(input, target),
                        (String) input.get("startDate"),
                        (String) input.get("endDate"));
                return formatOHLCVBars(bars);
            }
        };
    }

    public ToolCallback getTechnicalIndicatorsCallback() {
        return new AbstractToolCallback("get_technical_indicators",
                "获取A股股票的技术指标数据，包括均线（MA5/10/20/60/120）、MACD、RSI、KDJ、布林带、ATR、ADX等。适用场景：需要进行技术分析、判断趋势方向、寻找买卖点时调用。RSI>70超买、RSI<30超卖；ADX>25表示趋势较强。",
                buildInputSchema(
                        "startDate", "开始日期，格式 yyyy-MM-dd",
                        "endDate", "结束日期，格式 yyyy-MM-dd")) {
            @Override
            protected String doExecute(Map<String, Object> input, ToolContext toolContext) {
                TargetContext target = requireTarget(toolContext);
                TechnicalIndicatorsVO vo = provider.getTechnicalIndicators(
                        effectiveTicker(input, target),
                        (String) input.get("startDate"),
                        (String) input.get("endDate"));
                return formatTechnicalIndicators(vo);
            }
        };
    }

    public ToolCallback getFundamentalDataCallback() {
        return new AbstractToolCallback("get_fundamental_data",
                "获取A股股票的基本面数据，包括估值指标（PE、PB、PS、PEG）、盈利能力（ROE、毛利率、净利率）、财务数据（营收、净利润、EPS）、增长指标、现金流、偿债能力等。适用场景：需要进行价值投资分析、选股、基本面对比时调用。",
                buildInputSchema()) {
            @Override
            protected String doExecute(Map<String, Object> input, ToolContext toolContext) {
                TargetContext target = requireTarget(toolContext);
                FundamentalDataVO vo = provider.getFundamentalData(effectiveTicker(input, target));
                return formatFundamentalData(vo);
            }
        };
    }

    public ToolCallback getSentimentCallback() {
        return new AbstractToolCallback("get_sentiment",
                "获取A股股票的市场情绪数据，包括综合情绪评分、分析师评级、短期/中期/长期情绪趋势、看涨/看跌比例、恐惧贪婪指数等。适用场景：需要判断市场情绪、辅助择时决策时调用。",
                buildInputSchema()) {
            @Override
            protected String doExecute(Map<String, Object> input, ToolContext toolContext) {
                TargetContext target = requireTarget(toolContext);
                SentimentDataVO vo = provider.getSentiment(effectiveTicker(input, target));
                return formatSentiment(vo);
            }
        };
    }

    public ToolCallback getStockNewsCallback() {
        return new AbstractToolCallback("get_stock_news",
                "获取指定A股股票的近期新闻列表，包括新闻标题、来源、发布时间、摘要和情感得分。",
                buildInputSchemaWithTypes(
                        Map.of("limit", new ParamDef("integer", "返回条数上限，默认5条")))) {
            @Override
            protected String doExecute(Map<String, Object> input, ToolContext toolContext) {
                TargetContext target = requireTarget(toolContext);
                int limit = parseInteger(input.get("limit"), 5);
                List<NewsItemVO> news = provider.getNews(effectiveTicker(input, target), limit);
                return formatNews(news);
            }
        };
    }

    public ToolCallback searchStockByNameCallback() {
        return new AbstractToolCallback("search_stock_by_name",
                "根据股票中文名称搜索股票代码。当用户提到公司名但未提供股票代码时，必须调用此工具。适用场景：用户说'分析一下药明康德'时，需要先调用此工具获取股票代码。",
                buildInputSchema("name", "股票中文名称，支持模糊匹配，如 药明康德、贵州茅台、宁德时代、比亚迪")) {
            @Override
            protected String doExecute(Map<String, Object> input, ToolContext toolContext) {
                if (currentTarget(toolContext) != null) {
                    throw new IllegalStateException(
                            "IDENTITY_BOUNDARY_VIOLATION: stock search is disabled inside a trading run");
                }
                String name = (String) input.get("name");
                List<StockSearchResultVO> results = provider.searchByName(name);
                return formatSearchResults(results);
            }
        };
    }

    // ==================== ToolCallback 抽象基类 ====================

    private abstract class AbstractToolCallback implements ToolCallback {
        private final String name;
        private final String description;
        private final String inputSchema;

        AbstractToolCallback(String name, String description, String inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build();
        }

        @Override
        public final String call(String functionInput) {
            return call(functionInput, new ToolContext(Map.of()));
        }

        @Override
        public final String call(String functionInput, ToolContext toolContext) {
            try {
                ToolContext normalized = toolContext == null
                        ? new ToolContext(Map.of()) : toolContext;
                Map<String, Object> input = objectMapper.readValue(
                        functionInput, new TypeReference<Map<String, Object>>() {});
                return doExecute(input, normalized);
            } catch (Exception e) {
                log.error("Tool[{}] 执行失败: input={}, error={}", name, functionInput, e.getMessage(), e);
                if (e instanceof IllegalStateException
                        && e.getMessage() != null
                        && e.getMessage().startsWith("IDENTITY_BOUNDARY_VIOLATION")) {
                    rolloutMonitor.recordIdentityBoundaryViolation();
                    throw (IllegalStateException) e;
                }
                return "工具执行失败: " + e.getMessage();
            }
        }

        protected abstract String doExecute(Map<String, Object> input, ToolContext toolContext);
    }

    // ==================== 辅助方法：构建 inputSchema JSON ====================

    private String buildInputSchema(String... pairs) {
        try {
            Map<String, Map<String, String>> properties = new java.util.LinkedHashMap<>();
            List<String> required = new java.util.ArrayList<>();
            for (int i = 0; i < pairs.length; i += 2) {
                String name = pairs[i];
                String desc = pairs[i + 1];
                properties.put(name, Map.of("type", "string", "description", desc));
                required.add(name);
            }
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", required
            );
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            log.error("构建 inputSchema 失败: {}", e.getMessage());
            return "{}";
        }
    }

    private String buildInputSchemaWithTypes(Map<String, ParamDef> params) {
        try {
            Map<String, Map<String, String>> properties = new java.util.LinkedHashMap<>();
            List<String> required = new java.util.ArrayList<>();
            for (Map.Entry<String, ParamDef> e : params.entrySet()) {
                properties.put(e.getKey(), Map.of("type", e.getValue().type(), "description", e.getValue().description()));
                required.add(e.getKey());
            }
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", required
            );
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            log.error("构建 inputSchema 失败: {}", e.getMessage());
            return "{}";
        }
    }

    private int parseInteger(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("无法将值 '{}' 解析为整数，使用默认值 {}", value, defaultValue);
            return defaultValue;
        }
    }

    private TargetContext requireTarget(ToolContext toolContext) {
        TargetContext target = currentTarget(toolContext);
        if (target == null) {
            throw new IllegalStateException(
                    "IDENTITY_BOUNDARY_VIOLATION: trading target context is missing");
        }
        return target;
    }

    private TargetContext currentTarget(ToolContext toolContext) {
        Object value = toolContext.getContext().get(TradingTargetContextKeys.TARGET_CONTEXT);
        if (value == null) {
            return null;
        }
        if (!(value instanceof TargetContext target)) {
            throw new IllegalStateException(
                    "IDENTITY_BOUNDARY_VIOLATION: trading target context has invalid type");
        }
        return target;
    }

    private String effectiveTicker(Map<String, Object> input, TargetContext target) {
        Object original = input.get("ticker");
        if (original != null && !target.targetId().equalsIgnoreCase(original.toString().trim())) {
            log.warn("TOOL_TARGET_OVERRIDDEN runId={} originalTicker={} effectiveTicker={}",
                    target.runId(), original, target.targetId());
            rolloutMonitor.recordToolTargetOverride();
        }
        return target.targetId();
    }

    // ==================== 格式化方法 ====================

    private String formatStockInfo(StockInfoVO vo) {
        if (vo == null) return "未找到该股票信息";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(nvl(vo.getName())).append(" (").append(vo.getTicker()).append(")\n");
        sb.append("- 交易所: ").append(nvl(vo.getExchange())).append("\n");
        if (vo.getCurrentPrice() != null) sb.append("- 当前价格: ").append(vo.getCurrentPrice()).append(" 元\n");
        if (vo.getPeRatio() != null) sb.append("- 市盈率(PE): ").append(String.format("%.2f", vo.getPeRatio())).append("\n");
        if (vo.getPbRatio() != null) sb.append("- 市净率(PB): ").append(String.format("%.2f", vo.getPbRatio())).append("\n");
        if (vo.getMarketCap() != null) sb.append("- 市值: ").append(vo.getMarketCap()).append(" 十亿美元\n");
        if (vo.getVolume() != null) sb.append("- 日成交量: ").append(formatVolume(vo.getVolume())).append("\n");
        if (vo.getWeek52High() != null) sb.append("- 52周最高: ").append(vo.getWeek52High()).append(" 元\n");
        if (vo.getWeek52Low() != null) sb.append("- 52周最低: ").append(vo.getWeek52Low()).append(" 元\n");
        return sb.toString();
    }

    private String formatOHLCVBars(List<OHLCVBarVO> bars) {
        if (bars == null || bars.isEmpty()) return "未找到K线数据";
        StringBuilder sb = new StringBuilder();
        sb.append("# 历史K线数据（共 ").append(bars.size()).append(" 条）\n\n");
        sb.append(String.format("%-12s %10s %10s %10s %10s %15s %15s %12s %12s%n",
                "日期", "开盘价", "最高价", "最低价", "收盘价", "成交量", "成交额", "涨跌额", "涨跌幅"));
        sb.append("--------------------------------------------------------------------------------------------------------------\n");
        for (OHLCVBarVO bar : bars) {
            sb.append(String.format("%-12s %10s %10s %10s %10s %15s %15s %12s %12s%n",
                    nvl(bar.getDate()),
                    bar.getOpen() != null ? bar.getOpen().toString() : "N/A",
                    bar.getHigh() != null ? bar.getHigh().toString() : "N/A",
                    bar.getLow() != null ? bar.getLow().toString() : "N/A",
                    bar.getClose() != null ? bar.getClose().toString() : "N/A",
                    bar.getVolume() != null ? formatVolume(bar.getVolume()) : "N/A",
                    bar.getAmount() != null ? bar.getAmount().toPlainString() : "N/A",
                    bar.getChange() != null ? bar.getChange().toPlainString() : "N/A",
                    bar.getPctChg() != null ? String.format("%.2f%%", bar.getPctChg()) : "N/A"));
        }
        return sb.toString();
    }

    private String formatTechnicalIndicators(TechnicalIndicatorsVO vo) {
        if (vo == null) return "未找到技术指标数据";
        StringBuilder sb = new StringBuilder("# 技术指标分析\n\n");

        sb.append("## 均线\n");
        appendLine(sb, "MA5", vo.getMa5());
        appendLine(sb, "MA10", vo.getMa10());
        appendLine(sb, "MA20", vo.getMa20());
        appendLine(sb, "MA60", vo.getMa60());
        appendLine(sb, "MA120", vo.getMa120());

        sb.append("\n## MACD\n");
        appendLine(sb, "MACD线", vo.getMacd());
        appendLine(sb, "Signal线", vo.getMacdSignal());
        appendLine(sb, "Histogram柱", vo.getMacdHistogram());

        sb.append("\n## RSI\n");
        appendRsi(sb, "RSI6", vo.getRsi6());
        appendLine(sb, "RSI12", vo.getRsi12());
        appendLine(sb, "RSI24", vo.getRsi24());

        sb.append("\n## KDJ\n");
        appendLine(sb, "K值", vo.getK());
        appendLine(sb, "D值", vo.getD());
        appendLine(sb, "J值", vo.getJ());

        sb.append("\n## 布林带\n");
        appendLine(sb, "上轨", vo.getBollUpper());
        appendLine(sb, "中轨", vo.getBollMiddle());
        appendLine(sb, "下轨", vo.getBollLower());

        sb.append("\n## 其他\n");
        appendLine(sb, "ATR", vo.getAtr());
        appendAdx(sb, "ADX", vo.getAdx());
        appendLine(sb, "成交量比", vo.getVolumeRatio());
        appendLine(sb, "5日均量", vo.getVolumeMa5() != null ? vo.getVolumeMa5().longValue() : null);

        return sb.toString();
    }

    private String formatFundamentalData(FundamentalDataVO vo) {
        if (vo == null) return "未找到基本面数据";
        StringBuilder sb = new StringBuilder("# 基本面数据\n\n");

        sb.append("## 估值指标\n");
        appendLine(sb, "市盈率(PE)", vo.getPeRatio(), "%.2f");
        appendLine(sb, "市净率(PB)", vo.getPbRatio(), "%.2f");
        appendLine(sb, "市销率(PS)", vo.getPsRatio(), "%.2f");
        appendPeg(sb, "PEG", vo.getPegRatio());

        sb.append("\n## 盈利能力\n");
        appendLine(sb, "ROE", vo.getRoe());
        appendLine(sb, "ROA", vo.getRoa());
        appendLine(sb, "毛利率", vo.getGrossMargin());
        appendLine(sb, "净利率", vo.getNetMargin());

        sb.append("\n## 财务数据\n");
        appendMoney(sb, "营收", vo.getRevenue());
        appendMoney(sb, "净利润", vo.getNetIncome());
        appendLine(sb, "每股收益(EPS)", vo.getEps());
        appendLine(sb, "每股股息(DPS)", vo.getDps());

        sb.append("\n## 增长指标\n");
        appendLine(sb, "营收增长率", vo.getRevenueGrowth());
        appendLine(sb, "净利润增长率", vo.getNetIncomeGrowth());

        sb.append("\n## 现金流\n");
        appendMoney(sb, "经营活动现金流", vo.getOperatingCashFlow());
        appendMoney(sb, "自由现金流", vo.getFreeCashFlow());

        sb.append("\n## 偿债能力\n");
        appendLine(sb, "资产负债率(%)", vo.getDebtToAssets());
        appendLine(sb, "流动比率", vo.getCurrentRatio(), "%.2f");

        return sb.toString();
    }

    private String formatSentiment(SentimentDataVO vo) {
        if (vo == null) return "未找到情绪数据";
        StringBuilder sb = new StringBuilder("# 市场情绪数据\n\n");

        sb.append("## 综合情绪\n");
        appendOverall(sb, "综合情绪评分", vo.getOverallScore());

        sb.append("\n## 分类情绪\n");
        appendLine(sb, "分析师评级", vo.getAnalystScore());
        appendLine(sb, "短期情绪(7天)", vo.getShortTermScore());
        appendLine(sb, "中期情绪(30天)", vo.getMediumTermScore());
        appendLine(sb, "长期情绪(90天)", vo.getLongTermScore());

        sb.append("\n## 市场结构\n");
        appendLine(sb, "看涨比例", vo.getBullRatio());
        appendLine(sb, "看跌比例", vo.getBearRatio());
        if (vo.getFearGreedIndex() != null) {
            sb.append("- 恐惧贪婪指数: ").append(vo.getFearGreedIndex());
            if (vo.getFearGreedIndex() >= 75) sb.append(" 【极度贪婪】");
            else if (vo.getFearGreedIndex() >= 55) sb.append(" 【贪婪】");
            else if (vo.getFearGreedIndex() >= 45) sb.append(" 【中性】");
            else if (vo.getFearGreedIndex() >= 25) sb.append(" 【恐惧】");
            else sb.append(" 【极度恐惧】");
            sb.append("\n");
        }

        if (vo.getPlatformSentiments() != null && !vo.getPlatformSentiments().isEmpty()) {
            sb.append("\n## 平台情绪\n");
            vo.getPlatformSentiments().forEach((p, s) ->
                    sb.append("- ").append(p).append(": ")
                            .append(s != null ? String.format("%.4f", s) : "N/A").append("\n"));
        }

        return sb.toString();
    }

    private String formatNews(List<NewsItemVO> news) {
        if (news == null || news.isEmpty()) return "未找到相关新闻";
        StringBuilder sb = new StringBuilder();
        sb.append("# 股票新闻（共 ").append(news.size()).append(" 条）\n\n");
        for (int i = 0; i < news.size(); i++) {
            NewsItemVO item = news.get(i);
            sb.append("## ").append(i + 1).append(". ").append(nvl(item.getTitle())).append("\n");
            sb.append("- 来源: ").append(nvl(item.getSource()));
            if (item.getPublishTime() != null) sb.append(" | ").append(item.getPublishTime());
            sb.append("\n");
            if (item.getSentimentScore() != null) {
                sb.append("- 情感: ").append(String.format("%.4f", item.getSentimentScore()));
                if (item.getSentimentScore() > 0.3) sb.append(" 【正面】");
                else if (item.getSentimentScore() < -0.3) sb.append(" 【负面】");
                else sb.append(" 【中性】");
                sb.append("\n");
            }
            if (item.getSummary() != null) sb.append("- 摘要: ").append(item.getSummary()).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatSearchResults(List<StockSearchResultVO> results) {
        if (results == null || results.isEmpty()) {
            return "未找到匹配的股票，请尝试其他名称";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 搜索结果（共 ").append(results.size()).append(" 条）\n\n");
        for (int i = 0; i < results.size(); i++) {
            StockSearchResultVO r = results.get(i);
            sb.append(i + 1).append(". ").append(r.getName())
                    .append(" (").append(r.getTicker()).append(") [");
            sb.append(formatExchange(r.getExchange())).append("-").append(nvl(r.getMarket())).append("]\n");
        }
        return sb.toString();
    }

    private String formatExchange(String exchange) {
        if (exchange == null) return "未知";
        return switch (exchange) {
            case "SSE" -> "上交所";
            case "SZSE" -> "深交所";
            case "BSE" -> "北交所";
            default -> exchange;
        };
    }

    // ==================== 辅助格式化方法 ====================

    private String nvl(Object v) { return v != null ? v.toString() : "N/A"; }

    private String fbd(BigDecimal v) {
        return v != null ? v.setScale(4, RoundingMode.HALF_UP).toString() : "N/A";
    }

    private String fdl(Double v) {
        return v != null ? String.format("%.4f", v) : "N/A";
    }

    private String formatVolume(Long v) {
        if (v == null) return "N/A";
        if (v >= 100_000_000) return String.format("%.2f亿", v / 1_0000_0000.0);
        if (v >= 10_000) return String.format("%.2f万", v / 1_0000.0);
        return v.toString();
    }

    private String fmoney(BigDecimal v) {
        if (v == null) return "N/A";
        if (v.abs().compareTo(new BigDecimal("100000000")) >= 0) {
            return String.format("%.2f亿", v.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP));
        }
        if (v.abs().compareTo(new BigDecimal("10000")) >= 0) {
            return String.format("%.2f万", v.divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP));
        }
        return v.setScale(2, RoundingMode.HALF_UP).toString() + " 元";
    }

    private void appendLine(StringBuilder sb, String label, BigDecimal v) {
        sb.append("- ").append(label).append(": ").append(fbd(v)).append("\n");
    }

    private void appendLine(StringBuilder sb, String label, BigDecimal v, String fmt) {
        sb.append("- ").append(label).append(": ")
                .append(v != null ? String.format(fmt, v) : "N/A").append("\n");
    }

    private void appendLine(StringBuilder sb, String label, Long v) {
        sb.append("- ").append(label).append(": ").append(formatVolume(v)).append("\n");
    }

    private void appendLine(StringBuilder sb, String label, Double v) {
        sb.append("- ").append(label).append(": ").append(fdl(v)).append("\n");
    }

    private void appendLine(StringBuilder sb, String label, Double v, String fmt) {
        sb.append("- ").append(label).append(": ")
                .append(v != null ? String.format(fmt, v) : "N/A").append("\n");
    }

    private void appendRsi(StringBuilder sb, String label, Double v) {
        sb.append("- ").append(label).append(": ").append(fdl(v));
        if (v != null) {
            if (v > 70) sb.append(" 【超买】");
            else if (v < 30) sb.append(" 【超卖】");
        }
        sb.append("\n");
    }

    private void appendAdx(StringBuilder sb, String label, Double v) {
        sb.append("- ").append(label).append(": ").append(fdl(v));
        if (v != null) {
            if (v > 25) sb.append(" 【趋势较强】");
            else if (v < 20) sb.append(" 【趋势较弱】");
        }
        sb.append("\n");
    }

    private void appendPeg(StringBuilder sb, String label, Double v) {
        sb.append("- ").append(label).append(": ").append(fdl(v));
        if (v != null) {
            if (v < 1) sb.append(" 【低估】");
            else if (v > 2) sb.append(" 【高估】");
        }
        sb.append("\n");
    }

    private void appendMoney(StringBuilder sb, String label, BigDecimal v) {
        sb.append("- ").append(label).append(": ").append(fmoney(v)).append("\n");
    }

    private void appendOverall(StringBuilder sb, String label, Double v) {
        sb.append("- ").append(label).append(": ").append(fdl(v));
        if (v != null) {
            if (v > 0.5) sb.append(" 【偏多】");
            else if (v < -0.5) sb.append(" 【偏空】");
            else sb.append(" 【中性】");
        }
        sb.append("\n");
    }
}
