package denny.ai.agent.trading.domain.guard;

import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 对已提交的原始财务数据执行确定性合理性检查。
 *
 * <p>所有比率均使用百分数值契约，例如 12.5 表示 12.5%。行业阈值只产生
 * 数据质量警告，不能单独判定标的身份不一致。</p>
 */
@Slf4j
@Component
public class DataSanityGuard {

    private static final double ROE_EXTREME_HIGH = 50.0;
    private static final double DEBT_TO_ASSETS_EXTREME_LOW = 10.0;
    private static final double DEBT_TO_ASSETS_EXTREME_HIGH = 99.0;

    public List<String> check(TradingContextVO context) {
        List<String> warnings = new ArrayList<>();
        if (context == null || context.getStockInfo() == null) {
            return warnings;
        }

        FundamentalDataVO fundamental = context.getFundamentalReport() == null
                ? null : context.getFundamentalReport().getRawData();
        if (fundamental == null) {
            return warnings;
        }

        StockInfoVO stockInfo = context.getStockInfo();
        checkRoe(stockInfo, fundamental, warnings);
        checkGrossMargin(stockInfo, fundamental, warnings);
        checkDebtToAssets(stockInfo, fundamental, warnings);

        if (!warnings.isEmpty()) {
            log.warn("Data sanity warnings: ticker={}, industry={}, count={}",
                    stockInfo.getTicker(), stockInfo.getIndustry(), warnings.size());
        }
        return List.copyOf(warnings);
    }

    private void checkRoe(StockInfoVO stockInfo,
                          FundamentalDataVO data,
                          List<String> warnings) {
        Double roe = data.getRoe();
        if (roe == null) {
            return;
        }
        if (roe > ROE_EXTREME_HIGH) {
            warnings.add(String.format("ROE %.2f%% 超出常见范围，请核对原始财务数据", roe));
        }

        String industry = stockInfo.getIndustry();
        if (matches(industry, "保险", "insurance") && (roe < 3.0 || roe > 35.0)) {
            warnings.add(String.format("ROE %.2f%% 偏离保险行业常见范围", roe));
        } else if (matches(industry, "银行", "bank") && (roe < 5.0 || roe > 25.0)) {
            warnings.add(String.format("ROE %.2f%% 偏离银行业常见范围", roe));
        } else if (matches(industry, "半导体", "semiconductor") && (roe < -10.0 || roe > 150.0)) {
            warnings.add(String.format("ROE %.2f%% 偏离半导体行业常见范围", roe));
        }
    }

    private void checkGrossMargin(StockInfoVO stockInfo,
                                  FundamentalDataVO data,
                                  List<String> warnings) {
        Double grossMargin = data.getGrossMargin();
        if (grossMargin == null) {
            return;
        }
        String industry = stockInfo.getIndustry();
        if (matches(industry, "保险", "银行", "证券", "insurance", "bank", "securities")
                && grossMargin > 30.0) {
            warnings.add(String.format("毛利率 %.2f%% 不适合直接用于%s行业判断，请核对指标语义",
                    grossMargin, industry));
        }
    }

    private void checkDebtToAssets(StockInfoVO stockInfo,
                                   FundamentalDataVO data,
                                   List<String> warnings) {
        Double debtToAssets = data.getDebtToAssets();
        if (debtToAssets == null) {
            return;
        }
        if (matches(stockInfo.getIndustry(), "保险", "insurance")
                && debtToAssets < DEBT_TO_ASSETS_EXTREME_LOW) {
            warnings.add(String.format("资产负债率 %.2f%% 对保险行业异常偏低，请核对原始数据",
                    debtToAssets));
        }
        if (debtToAssets > DEBT_TO_ASSETS_EXTREME_HIGH || debtToAssets < 0.0) {
            warnings.add(String.format("资产负债率 %.2f%% 超出有效范围", debtToAssets));
        }
    }

    private boolean matches(String industry, String... aliases) {
        if (industry == null || industry.isBlank()) {
            return false;
        }
        String normalized = industry.toLowerCase(java.util.Locale.ROOT);
        for (String alias : aliases) {
            if (normalized.contains(alias.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
