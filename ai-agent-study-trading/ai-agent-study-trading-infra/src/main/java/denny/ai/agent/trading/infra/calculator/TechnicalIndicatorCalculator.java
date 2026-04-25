package denny.ai.agent.trading.infra.calculator;

import denny.ai.agent.trading.api.vo.OHLCVBarVO;
import denny.ai.agent.trading.api.vo.TechnicalIndicatorsVO;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 技术指标计算器。
 * <p>
 * 基于 OHLCV 数据计算各种技术指标。
 * <p>
 * 注意：测试环境请确保 logback-classic 依赖可用。
 */
@Slf4j
@Component
public class TechnicalIndicatorCalculator {

    /**
     * 计算所有技术指标
     */
    public TechnicalIndicatorsVO calculate(String ticker, List<OHLCVBarVO> bars) {
        if (bars == null || bars.isEmpty()) {
            log.warn("No bars provided for technical indicator calculation");
            return TechnicalIndicatorsVO.builder().ticker(ticker).build();
        }

        List<Double> closes = bars.stream()
                .map(b -> b.getClose().doubleValue())
                .toList();
        List<Double> volumes = bars.stream()
                .map(b -> b.getVolume() != null ? b.getVolume().doubleValue() : 0.0)
                .toList();
        List<Double> highs = bars.stream()
                .map(b -> b.getHigh() != null ? b.getHigh().doubleValue() : b.getClose().doubleValue())
                .toList();
        List<Double> lows = bars.stream()
                .map(b -> b.getLow() != null ? b.getLow().doubleValue() : b.getClose().doubleValue())
                .toList();

        Double[] kdj = calculateKDJ(closes, 9);
        Double[] boll = calculateBollingerBands(closes, 20, 2);
        Double atr = calculateATR(highs, lows, closes, 14);
        Double adx = calculateADX(highs, lows, closes, 14);
        Double[] volMa = calculateVolumeMA(volumes, 5);

        return TechnicalIndicatorsVO.builder()
                .ticker(ticker)
                .ma5(toBd(calculateSMA(closes, 5)))
                .ma10(toBd(calculateSMA(closes, 10)))
                .ma20(toBd(calculateSMA(closes, 20)))
                .ma60(toBd(calculateSMA(closes, 60)))
                .ma120(toBd(calculateSMA(closes, 120)))
                .macd(toBd(calculateMACD(closes)))
                .macdSignal(toBd(calculateSignalLine(closes)))
                .macdHistogram(toBd(calculateMACDHistogram(closes)))
                .rsi6(calculateRSI(closes, 6))
                .rsi12(calculateRSI(closes, 12))
                .rsi24(calculateRSI(closes, 24))
                .k(kdj != null ? kdj[0] : null)
                .d(kdj != null ? kdj[1] : null)
                .j(kdj != null ? kdj[2] : null)
                .bollUpper(boll != null ? toBd(boll[0]) : null)
                .bollMiddle(boll != null ? toBd(boll[1]) : null)
                .bollLower(boll != null ? toBd(boll[2]) : null)
                .volumeRatio(volMa != null ? volMa[0] : null)
                .volumeMa5(volMa != null ? toBd(volMa[1]) : null)
                .atr(toBd(atr))
                .adx(adx)
                .build();
    }

    /**
     * 计算简单移动平均线 (SMA)
     */
    public Double calculateSMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            return null;
        }

        double sum = 0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            sum += prices.get(i);
        }
        return round(sum / period);
    }

    /**
     * 计算指数移动平均线 (EMA)
     */
    public Double calculateEMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            return null;
        }

        double multiplier = 2.0 / (period + 1);
        double ema = 0;

        // 初始 EMA 为 SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += prices.get(i);
        }
        ema = sum / period;

        // 后续 EMA
        for (int i = period; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }

        return round(ema);
    }

    /**
     * 计算 MACD
     */
    public Double calculateMACD(List<Double> prices) {
        Double ema12 = calculateEMA(prices, 12);
        Double ema26 = calculateEMA(prices, 26);

        if (ema12 == null || ema26 == null) {
            return null;
        }

        return round(ema12 - ema26);
    }

    /**
     * 计算 MACD 信号线（MACD 的 EMA9）
     */
    public Double calculateSignalLine(List<Double> prices) {
        if (prices.size() < 35) { // 26 + 9
            return null;
        }

        Double ema12 = calculateEMA(prices, 12);
        Double ema26 = calculateEMA(prices, 26);

        if (ema12 == null || ema26 == null) {
            return null;
        }

        // 计算 MACD 序列
        int macdLength = prices.size() - 26;
        List<Double> macdSeries = new java.util.ArrayList<>(macdLength);
        double[] ema12Series = new double[prices.size()];
        double[] ema26Series = new double[prices.size()];
        double mult12 = 2.0 / 13.0;
        double mult26 = 2.0 / 27.0;

        for (int i = 0; i < 12; i++) {
            ema12Series[i] = 0;
            ema26Series[i] = 0;
        }
        double sum12 = 0, sum26 = 0;
        for (int i = 0; i < 12; i++) sum12 += prices.get(i);
        for (int i = 0; i < 26; i++) sum26 += prices.get(i);
        ema12Series[11] = sum12 / 12;
        ema26Series[25] = sum26 / 26;
        for (int i = 12; i < prices.size(); i++) {
            ema12Series[i] = (prices.get(i) - ema12Series[i - 1]) * mult12 + ema12Series[i - 1];
        }
        for (int i = 26; i < prices.size(); i++) {
            ema26Series[i] = (prices.get(i) - ema26Series[i - 1]) * mult26 + ema26Series[i - 1];
        }
        for (int i = 26; i < prices.size(); i++) {
            macdSeries.add(round(ema12Series[i] - ema26Series[i]));
        }

        if (macdSeries.size() < 9) {
            return null;
        }

        // MACD 的 EMA9
        double signalMult = 2.0 / 10.0;
        double signal = 0;
        double sumMacd = 0;
        for (int i = 0; i < 9; i++) {
            sumMacd += macdSeries.get(i);
        }
        signal = sumMacd / 9;
        for (int i = 9; i < macdSeries.size(); i++) {
            signal = (macdSeries.get(i) - signal) * signalMult + signal;
        }

        return round(signal);
    }

    /**
     * 计算相对强弱指数 (RSI)
     */
    public Double calculateRSI(List<Double> prices, int period) {
        if (prices.size() < period + 1) {
            return null;
        }

        double gains = 0;
        double losses = 0;

        // 计算初始平均涨跌幅
        for (int i = prices.size() - period; i < prices.size() - 1; i++) {
            double change = prices.get(i + 1) - prices.get(i);
            if (change > 0) {
                gains += change;
            } else {
                losses -= change;
            }
        }

        double avgGain = gains / period;
        double avgLoss = losses / period;

        if (avgLoss == 0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;
        return round(100 - (100 / (1 + rs)));
    }

    /**
     * 计算布林带
     */
    public Double[] calculateBollingerBands(List<Double> prices, int period, int multiplier) {
        Double sma = calculateSMA(prices, period);
        if (sma == null) {
            return null;
        }

        // 计算标准差
        double sumSquares = 0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            double diff = prices.get(i) - sma;
            sumSquares += diff * diff;
        }
        double stdDev = Math.sqrt(sumSquares / period);

        double upper = sma + multiplier * stdDev;
        double lower = sma - multiplier * stdDev;

        return new Double[]{round(upper), round(sma), round(lower)};
    }

    /**
     * 计算 KDJ 指标
     */
    public Double[] calculateKDJ(List<Double> closes, int period) {
        if (closes.size() < period) {
            return null;
        }

        int n = closes.size();
        double[] highs = new double[n];
        double[] lows = new double[n];
        for (int i = 0; i < n; i++) {
            highs[i] = closes.get(i);
            lows[i] = closes.get(i);
        }

        double[] rsv = new double[n];
        for (int i = period - 1; i < n; i++) {
            double maxHigh = Double.NEGATIVE_INFINITY;
            double minLow = Double.POSITIVE_INFINITY;
            for (int j = i - period + 1; j <= i; j++) {
                if (highs[j] > maxHigh) maxHigh = highs[j];
                if (lows[j] < minLow) minLow = lows[j];
            }
            double close = closes.get(i);
            if (maxHigh == minLow) {
                rsv[i] = 50.0;
            } else {
                rsv[i] = (close - minLow) / (maxHigh - minLow) * 100;
            }
        }

        double k = 50.0, d = 50.0;
        for (int i = period - 1; i < n; i++) {
            k = (2.0 / 3.0) * k + (1.0 / 3.0) * rsv[i];
            d = (2.0 / 3.0) * d + (1.0 / 3.0) * k;
            double j = 3.0 * k - 2.0 * d;
            if (i == n - 1) {
                return new Double[]{round(k), round(d), round(j)};
            }
        }
        return new Double[]{50.0, 50.0, 50.0};
    }

    /**
     * 计算平均真实波幅 (ATR)
     */
    public Double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        int n = closes.size();
        if (n < period + 1) {
            return null;
        }

        double[] tr = new double[n];
        tr[0] = highs.get(0) - lows.get(0);
        for (int i = 1; i < n; i++) {
            tr[i] = Math.max(
                    highs.get(i) - lows.get(i),
                    Math.max(
                            Math.abs(highs.get(i) - closes.get(i - 1)),
                            Math.abs(lows.get(i) - closes.get(i - 1))
                    )
            );
        }

        double atr = 0;
        for (int i = 0; i < period; i++) {
            atr += tr[i];
        }
        atr /= period;
        for (int i = period; i < n; i++) {
            atr = (atr * (period - 1) + tr[i]) / period;
        }
        return round(atr);
    }

    /**
     * 计算平均趋向指数 (ADX)
     */
    public Double calculateADX(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        int n = closes.size();
        if (n < period * 2 + 1) {
            return null;
        }

        double[] dmPlus = new double[n];
        double[] dmMinus = new double[n];
        double[] tr = new double[n];

        tr[0] = highs.get(0) - lows.get(0);
        for (int i = 1; i < n; i++) {
            double highDiff = highs.get(i) - highs.get(i - 1);
            double lowDiff = lows.get(i - 1) - lows.get(i);

            dmPlus[i] = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
            dmMinus[i] = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;
            tr[i] = Math.max(
                    highs.get(i) - lows.get(i),
                    Math.max(
                            Math.abs(highs.get(i) - closes.get(i - 1)),
                            Math.abs(lows.get(i) - closes.get(i - 1))
                    )
            );
        }

        double[] smoothedDmPlus = new double[n];
        double[] smoothedDmMinus = new double[n];
        double[] smoothedTr = new double[n];

        for (int i = 0; i < period; i++) {
            smoothedTr[i] = tr[i];
            smoothedDmPlus[i] = dmPlus[i];
            smoothedDmMinus[i] = dmMinus[i];
        }
        for (int i = period; i < n; i++) {
            smoothedTr[i] = smoothedTr[i - 1] - smoothedTr[i - 1] / period + tr[i];
            smoothedDmPlus[i] = smoothedDmMinus[i - 1] - smoothedDmMinus[i - 1] / period + dmPlus[i];
            smoothedDmMinus[i] = smoothedDmMinus[i - 1] - smoothedDmMinus[i - 1] / period + dmMinus[i];
        }

        double[] diPlus = new double[n];
        double[] diMinus = new double[n];
        for (int i = period - 1; i < n; i++) {
            if (smoothedTr[i] != 0) {
                diPlus[i] = (smoothedDmPlus[i] / smoothedTr[i]) * 100;
                diMinus[i] = (smoothedDmMinus[i] / smoothedTr[i]) * 100;
            } else {
                diPlus[i] = 0;
                diMinus[i] = 0;
            }
        }

        double[] dx = new double[n];
        for (int i = period - 1; i < n; i++) {
            double sumDi = diPlus[i] + diMinus[i];
            if (sumDi == 0) {
                dx[i] = 0;
            } else {
                dx[i] = Math.abs(diPlus[i] - diMinus[i]) / sumDi * 100;
            }
        }

        double adx = 0;
        int startIdx = period * 2 - 1;
        for (int i = startIdx; i < startIdx + period; i++) {
            adx += dx[i];
        }
        adx /= period;
        for (int i = startIdx + period; i < n; i++) {
            adx = (adx * (period - 1) + dx[i]) / period;
        }
        return round(adx);
    }

    /**
     * 计算成交量指标：volRatio = 当前成交量 / 5日均量，volMa5 = 5日均量
     */
    public Double[] calculateVolumeMA(List<Double> volumes, int period) {
        if (volumes == null || volumes.size() < period) {
            return null;
        }

        double sum = 0;
        for (int i = volumes.size() - period; i < volumes.size(); i++) {
            sum += volumes.get(i);
        }
        double volMa5 = sum / period;
        double currentVol = volumes.get(volumes.size() - 1);
        double volRatio = (volMa5 > 0) ? round(currentVol / volMa5) : null;

        return new Double[]{volRatio, volMa5};
    }

    private Double round(Double value) {
        if (value == null) return null;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal toBd(Double value) {
        if (value == null) return null;
        return BigDecimal.valueOf(value);
    }

    public Double calculateMACDHistogram(List<Double> prices) {
        Double macd = calculateMACD(prices);
        Double signal = calculateSignalLine(prices);
        if (macd == null || signal == null) {
            return null;
        }
        return round(macd - signal);
    }
}
