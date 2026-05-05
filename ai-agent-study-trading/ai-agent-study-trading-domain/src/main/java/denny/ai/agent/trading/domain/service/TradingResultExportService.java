package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.domain.model.valobj.TradingResultVO;
import denny.ai.agent.trading.api.vo.NewsReportVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 交易结果导出服务。
 * <p>
 * 将投资分析结果异步渲染为 Markdown 文档并写入文件，不阻塞主流程。
 */
@Slf4j
@Service
public class TradingResultExportService {

    private static final String OUTPUT_DIR = "docs/trading-agent";

    @Async("tradingTaskExecutor")
    public void export(TradingResultVO result) {
        try {
            String markdown = renderMarkdown(result);
            String fileName = result.getTicker() + "-" + sanitizeFileName(result.getName()) + ".md";
            Path filePath = Paths.get(OUTPUT_DIR, fileName);

            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, markdown, StandardCharsets.UTF_8);

            log.info("交易结果文档已导出: {}", filePath);
        } catch (Exception e) {
            log.error("导出交易结果文档失败: ticker={}", result.getTicker(), e);
        }
    }

    private String renderMarkdown(TradingResultVO r) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(r.getName()).append(" (").append(r.getTicker()).append(") 投资分析报告\n\n");
        sb.append("- **交易所**: ").append(nullSafe(r.getExchange())).append("\n");
        sb.append("- **当前价格**: ").append(r.getCurrentPrice() != null ? r.getCurrentPrice() : "N/A").append("\n");
        sb.append("- **生成时间**: ").append(r.getGeneratedAt()).append("\n\n");

        if (r.getFundamentalReport() != null) {
            TradingResultVO.FundamentalSummary f = r.getFundamentalReport();
            sb.append("## 基本面分析\n\n");
            sb.append("| 指标 | 值 |\n|--------|------|\n");
            sb.append("| 评分 | ").append(f.getRating()).append("/5 |\n");
            if (f.getKeyFindings() != null && !f.getKeyFindings().isEmpty()) {
                sb.append("| 主要发现 | ").append(join(f.getKeyFindings(), "；")).append(" |\n");
            }
            if (f.getRiskWarnings() != null && !f.getRiskWarnings().isEmpty()) {
                sb.append("| 风险提示 | ").append(join(f.getRiskWarnings(), "；")).append(" |\n");
            }
            sb.append("\n").append(nullSafe(f.getSummary())).append("\n\n");
        }

        if (r.getTechnicalReport() != null) {
            TradingResultVO.TechnicalSummary t = r.getTechnicalReport();
            sb.append("## 技术面分析\n\n");
            sb.append("| 指标 | 值 |\n|--------|------|\n");
            sb.append("| 评分 | ").append(t.getRating()).append("/5 |\n");
            sb.append("| 趋势信号 | ").append(nullSafe(t.getTrendSignal())).append(" |\n");
            if (t.getKeyPatterns() != null && !t.getKeyPatterns().isEmpty()) {
                sb.append("| 关键形态 | ").append(join(t.getKeyPatterns(), "；")).append(" |\n");
            }
            sb.append("\n").append(nullSafe(t.getSummary())).append("\n\n");
        }

        if (r.getSentimentReport() != null) {
            TradingResultVO.SentimentSummary s = r.getSentimentReport();
            sb.append("## 情绪面分析\n\n");
            sb.append("| 指标 | 值 |\n|--------|------|\n");
            sb.append("| 评分 | ").append(s.getRating()).append("/5 |\n");
            sb.append("| 情绪得分 | ").append(s.getSentimentScore()).append(" |\n");
            if (s.getKeySentiments() != null && !s.getKeySentiments().isEmpty()) {
                sb.append("| 关键情绪 | ").append(join(s.getKeySentiments(), "；")).append(" |\n");
            }
            sb.append("\n").append(nullSafe(s.getSummary())).append("\n\n");
        }

        if (r.getNewsReport() != null) {
            TradingResultVO.NewsSummary n = r.getNewsReport();
            sb.append("## 新闻面分析\n\n");
            sb.append("| 指标 | 值 |\n|--------|------|\n");
            sb.append("| 评分 | ").append(n.getRating()).append("/5 |\n");
            sb.append("| 整体情绪 | ").append(nullSafe(n.getOverallSentiment())).append(" |\n");
            if (n.getNewsThemes() != null && !n.getNewsThemes().isEmpty()) {
                List<String> themeStrings = new ArrayList<>();
                for (NewsReportVO.NewsThemeVO theme : n.getNewsThemes()) {
                    themeStrings.add(theme.getTheme() + "(" + nullSafe(theme.getSentiment()) + ", " + nullSafe(theme.getImpactLevel()) + ")");
                }
                sb.append("| 新闻主题 | ").append(join(themeStrings, "；")).append(" |\n");
            }
            sb.append("\n").append(nullSafe(n.getSummary())).append("\n\n");
        }

        if (r.getInvestmentDebate() != null) {
            TradingResultVO.InvestmentDebateSummary d = r.getInvestmentDebate();
            sb.append("## 投资辩论结论\n\n");
            sb.append("| 指标 | 值 |\n|--------|------|\n");
            sb.append("| 综合评分 | ").append(d.getOverallScore()).append(" |\n\n");
            if (d.getBullArguments() != null && !d.getBullArguments().isEmpty()) {
                sb.append("### 多头观点\n\n");
                for (int i = 0; i < d.getBullArguments().size(); i++) {
                    sb.append(i + 1).append(". ").append(d.getBullArguments().get(i)).append("\n");
                }
                sb.append("\n");
            }
            if (d.getBearArguments() != null && !d.getBearArguments().isEmpty()) {
                sb.append("### 空头观点\n\n");
                for (int i = 0; i < d.getBearArguments().size(); i++) {
                    sb.append(i + 1).append(". ").append(d.getBearArguments().get(i)).append("\n");
                }
                sb.append("\n");
            }
            sb.append(nullSafe(d.getConclusion())).append("\n\n");
        }

        if (r.getInvestmentPlan() != null) {
            TradingResultVO.InvestmentPlanSummary p = r.getInvestmentPlan();
            sb.append("## 投资建议\n\n");
            sb.append("| 项目 | 值 |\n|--------|------|\n");
            sb.append("| 操作 | **").append(nullSafe(p.getAction())).append("** |\n");
            sb.append("| 建议仓位 | ").append(p.getPositionRatio() != null ? (int) (p.getPositionRatio() * 100) + "%" : "N/A").append(" |\n");
            sb.append("| 入场价格区间 | ").append(nullSafe(p.getEntryPriceRange())).append(" |\n");
            sb.append("| 止损价 | ").append(nullSafe(p.getStopLossPrice())).append(" |\n");
            sb.append("| 止盈价 | ").append(nullSafe(p.getTakeProfitPrice())).append(" |\n");
            sb.append("| 持仓周期 | ").append(nullSafe(p.getHoldingPeriod())).append(" |\n");
            sb.append("| 风险收益比 | ").append(p.getRiskRewardRatio() != null ? "1:" + p.getRiskRewardRatio() : "N/A").append(" |\n");
            sb.append("\n");
        }

        return sb.toString();
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }

    private String join(Collection<?> items, String delimiter) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                sb.append(delimiter);
            }
            sb.append(item);
            first = false;
        }
        return sb.toString();
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
