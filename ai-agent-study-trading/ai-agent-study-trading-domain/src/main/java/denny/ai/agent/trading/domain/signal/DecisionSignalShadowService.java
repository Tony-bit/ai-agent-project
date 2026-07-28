package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.NewsReportVO;
import denny.ai.agent.trading.api.vo.SentimentReportVO;
import denny.ai.agent.trading.api.vo.TechnicalReportVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSet;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSource;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import denny.ai.agent.trading.api.metrics.TradingRolloutMonitor;

import java.util.Objects;

@Slf4j
@Service
public class DecisionSignalShadowService {

    private final TradingRolloutMonitor rolloutMonitor;

    private final FundamentalRatingAlgorithm fundamental = new FundamentalRatingAlgorithm();
    private final TechnicalSignalAlgorithm technical = new TechnicalSignalAlgorithm();
    private final SentimentRatingAlgorithm sentiment = new SentimentRatingAlgorithm();
    private final NewsRatingAlgorithm news = new NewsRatingAlgorithm();

    public DecisionSignalShadowService() {
        this(new TradingRolloutMonitor());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionSignalShadowService(TradingRolloutMonitor rolloutMonitor) {
        this.rolloutMonitor = rolloutMonitor;
    }

    public DecisionSignalSet calculate(TradingContextVO context) {
        Objects.requireNonNull(context, "context");
        FundamentalReportVO fundamentalReport = context.getFundamentalReport();
        TechnicalReportVO technicalReport = context.getTechnicalReport();
        SentimentReportVO sentimentReport = context.getSentimentReport();
        NewsReportVO newsReport = context.getNewsReport();

        TechnicalSignalAlgorithm.TechnicalSignals technicalSignals = technical.calculate(
                technicalReport == null ? null : technicalReport.getIndicators());
        SentimentRatingAlgorithm.SentimentSignals sentimentSignals = sentiment.calculate(
                sentimentReport == null ? null : sentimentReport.getRawData());
        NewsRatingAlgorithm.NewsSignals newsSignals = news.calculate(
                newsReport == null ? null : newsReport.getNewsItems());
        DecisionSignalSet signals = new DecisionSignalSet(
                fundamental.calculate(fundamentalReport == null ? null : fundamentalReport.getRawData()),
                technicalSignals.rating(), technicalSignals.trendSignal(),
                sentimentSignals.rating(), sentimentSignals.sentimentScore(),
                newsSignals.rating(), newsSignals.overallSentiment(),
                DecisionSignal.unavailable(DecisionSignalSource.DERIVED_V3,
                        "research-manager-score-v1", "research manager has not completed"),
                DecisionSignal.unavailable(DecisionSignalSource.DERIVED_V3,
                        "risk-score-v1", "risk management has not completed"));

        log.info("trading_signal_shadow runId={} targetId={} availableAnalysts={} "
                        + "v2Ratings=[{},{},{},{}] v3Ratings=[{},{},{},{}]",
                context.getTargetContext() == null ? "unknown" : context.getTargetContext().runId(),
                context.getTargetContext() == null ? "unknown" : context.getTargetContext().targetId(),
                signals.availableAnalystCount(),
                rating(fundamentalReport), rating(technicalReport), rating(sentimentReport), rating(newsReport),
                signals.fundamentalRating().value(), signals.technicalRating().value(),
                signals.sentimentRating().value(), signals.newsRating().value());
        rolloutMonitor.recordSignalComparison(different(fundamentalReport, signals.fundamentalRating().value())
                || different(technicalReport, signals.technicalRating().value())
                || different(sentimentReport, signals.sentimentRating().value())
                || different(newsReport, signals.newsRating().value()));
        return signals;
    }

    private Integer rating(Object report) {
        if (report instanceof FundamentalReportVO value) {
            return value.getRating();
        }
        if (report instanceof TechnicalReportVO value) {
            return value.getRating();
        }
        if (report instanceof SentimentReportVO value) {
            return value.getRating();
        }
        if (report instanceof NewsReportVO value) {
            return value.getRating();
        }
        return null;
    }

    private boolean different(Object report, Integer deterministicRating) {
        return !Objects.equals(rating(report), deterministicRating);
    }
}
