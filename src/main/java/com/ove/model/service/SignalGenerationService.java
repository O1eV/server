package com.ove.model.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ove.global.exception.BusinessException;
import com.ove.market.collector.CandleRepository;
import com.ove.market.collector.MinuteCandle;
import com.ove.model.client.ModelClient;
import com.ove.model.client.ModelDecision;
import com.ove.model.config.ModelProperties;
import com.ove.realtime.service.RealtimePublisher;
import com.ove.signal.domain.Signal;
import com.ove.signal.repository.SignalRepository;

/**
 * 1분봉이 마감되면 최근 캔들로 모델을 호출해 신호를 만든다. 저장·실시간 발행은 RealtimePublisher가 맡는다.
 * 모델 호출이 실패하면 마지막 신호를 유지하고 stale 표시만 보낸다.
 */
public class SignalGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SignalGenerationService.class);
    private static final Set<String> LABELS = Set.of("BUY", "HOLD", "SELL");
    private static final int SCALE = 8;

    private final ModelProperties properties;
    private final CandleRepository candleRepository;
    private final ModelClient modelClient;
    private final SignalRepository signalRepository;
    private final RealtimePublisher publisher;

    public SignalGenerationService(ModelProperties properties, CandleRepository candleRepository, ModelClient modelClient,
                                   SignalRepository signalRepository, RealtimePublisher publisher) {
        this.properties = properties;
        this.candleRepository = candleRepository;
        this.modelClient = modelClient;
        this.signalRepository = signalRepository;
        this.publisher = publisher;
    }

    // candleTime은 방금 마감된 봉의 시작 시각
    public void generate(String symbol, Instant candleTime) {
        // to는 포함하지 않으므로 마감된 봉이 들어가도록 1분을 더한다.
        List<MinuteCandle> candles = candleRepository.findMinuteCandles(symbol, null, candleTime.plusSeconds(60),
                properties.candleCount());
        if (candles.size() < ModelProperties.MIN_CANDLES) {
            log.debug("캔들이 부족해 모델 호출을 건너뜁니다: symbol={}, count={}", symbol, candles.size());
            return;
        }
        Signal latest = signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc(symbol).orElse(null);
        ModelDecision decision;
        try {
            decision = modelClient.predict(candles);
        } catch (BusinessException e) {
            publishStale(symbol, latest);
            return;
        }
        String label = decision.decision() == null ? "" : decision.decision().trim().toUpperCase(java.util.Locale.ROOT);
        double sum = decision.buy() + decision.hold() + decision.sell();
        if (!LABELS.contains(label) || !(sum > 0) || !Double.isFinite(sum)) {
            log.warn("모델 응답이 올바르지 않습니다: symbol={}", symbol);
            publishStale(symbol, latest);
            return;
        }
        // 소수 반올림 때문에 합이 1에서 조금 벗어날 수 있어 합 1로 맞춘다.
        Map<String, BigDecimal> probabilities = new LinkedHashMap<>();
        probabilities.put("buy", normalize(decision.buy(), sum));
        probabilities.put("hold", normalize(decision.hold(), sum));
        probabilities.put("sell", normalize(decision.sell(), sum));
        BigDecimal confidence = BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, decision.confidence())))
                .setScale(SCALE, RoundingMode.HALF_UP);
        // 이전과 다른 신호로 바뀐 때만 previousSignal을 둔다. 첫 신호·같은 신호가 이어질 때는 null(알림 없음).
        String previous = latest != null && !latest.getSignal().equals(label) ? latest.getSignal() : null;
        publisher.publishSignalChange(symbol, previous, label, probabilities, confidence, false);
    }

    private BigDecimal normalize(double value, double sum) {
        return BigDecimal.valueOf(Math.max(0.0, value) / sum).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private void publishStale(String symbol, Signal latest) {
        if (latest != null) {
            publisher.publishStaleSignal(symbol, latest.getSignal(), latest.getConfidence());
        }
    }
}
