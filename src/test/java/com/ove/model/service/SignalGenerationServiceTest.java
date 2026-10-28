package com.ove.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.collector.CandleRepository;
import com.ove.market.collector.MinuteCandle;
import com.ove.model.client.ModelClient;
import com.ove.model.client.ModelDecision;
import com.ove.model.config.ModelProperties;
import com.ove.realtime.service.RealtimePublisher;
import com.ove.signal.domain.Signal;
import com.ove.signal.repository.SignalRepository;

class SignalGenerationServiceTest {

    private static final Instant T = Instant.parse("2026-10-26T00:20:00Z");

    CandleRepository candleRepository = mock(CandleRepository.class);
    ModelClient modelClient = mock(ModelClient.class);
    SignalRepository signalRepository = mock(SignalRepository.class);
    RealtimePublisher publisher = mock(RealtimePublisher.class);
    SignalGenerationService service;

    @BeforeEach
    void setUp() {
        ModelProperties properties = new ModelProperties(true, "http://model", null, Duration.ofMillis(500), 30);
        service = new SignalGenerationService(properties, candleRepository, modelClient, signalRepository, publisher);
    }

    private void candles(int count) {
        List<MinuteCandle> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MinuteCandle("KRW-BTC", T.minusSeconds(60L * (count - i)), BigDecimal.TEN, BigDecimal.TEN,
                    BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE));
        }
        given(candleRepository.findMinuteCandles(eq("KRW-BTC"), any(), eq(T.plusSeconds(60)), eq(30))).willReturn(list);
    }

    private void latest(String signal) {
        Signal last = new Signal("KRW-BTC", null, signal, new BigDecimal("0.5"), new BigDecimal("0.3"),
                new BigDecimal("0.2"), new BigDecimal("0.5"), null, T.minusSeconds(60), null);
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC")).willReturn(Optional.of(last));
    }

    @Test
    @DisplayName("캔들이 15개 미만이면 모델을 부르지 않는다")
    void skipsWhenTooFewCandles() {
        candles(14);

        service.generate("KRW-BTC", T);

        verifyNoInteractions(modelClient, publisher);
    }

    @Test
    @DisplayName("첫 신호: previousSignal 없이 확률·확신도를 발행한다(알림 대상 아님)")
    @SuppressWarnings("unchecked")
    void firstSignal() {
        candles(15);
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC")).willReturn(Optional.empty());
        given(modelClient.predict(any())).willReturn(new ModelDecision("BUY", 0.7, 0.1, 0.2, 0.7));

        service.generate("KRW-BTC", T);

        ArgumentCaptor<Map<String, BigDecimal>> probabilities = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<BigDecimal> confidence = ArgumentCaptor.forClass(BigDecimal.class);
        verify(publisher).publishSignalChange(eq("KRW-BTC"), eq(null), eq("BUY"), probabilities.capture(),
                confidence.capture(), eq(false));
        assertThat(probabilities.getValue().get("buy")).isEqualByComparingTo("0.7");
        assertThat(probabilities.getValue().get("hold")).isEqualByComparingTo("0.2");
        assertThat(probabilities.getValue().get("sell")).isEqualByComparingTo("0.1");
        assertThat(confidence.getValue()).isEqualByComparingTo("0.7");
    }

    @Test
    @DisplayName("이전과 다른 신호면 previousSignal을 채우고, 같은 신호면 null")
    void previousSignalOnlyOnChange() {
        candles(15);
        latest("HOLD");
        given(modelClient.predict(any())).willReturn(new ModelDecision("BUY", 0.7, 0.1, 0.2, 0.7),
                new ModelDecision("HOLD", 0.2, 0.1, 0.7, 0.7));

        service.generate("KRW-BTC", T);
        verify(publisher).publishSignalChange(eq("KRW-BTC"), eq("HOLD"), eq("BUY"), any(), any(), eq(false));

        service.generate("KRW-BTC", T);
        verify(publisher).publishSignalChange(eq("KRW-BTC"), eq(null), eq("HOLD"), any(), any(), eq(false));
    }

    @Test
    @DisplayName("확률 합이 1에서 조금 벗어나도 합 1로 맞춰 저장 검증을 통과하게 한다")
    @SuppressWarnings("unchecked")
    void normalizesProbabilities() {
        candles(15);
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC")).willReturn(Optional.empty());
        given(modelClient.predict(any())).willReturn(new ModelDecision("sell", 0.33, 0.33, 0.33, 1.4));

        service.generate("KRW-BTC", T);

        ArgumentCaptor<Map<String, BigDecimal>> probabilities = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<BigDecimal> confidence = ArgumentCaptor.forClass(BigDecimal.class);
        verify(publisher).publishSignalChange(eq("KRW-BTC"), eq(null), eq("SELL"), probabilities.capture(),
                confidence.capture(), eq(false));
        BigDecimal sum = probabilities.getValue().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum.subtract(BigDecimal.ONE).abs()).isLessThan(new BigDecimal("0.001"));
        assertThat(confidence.getValue()).isEqualByComparingTo("1"); // 0~1로 제한
    }

    @Test
    @DisplayName("모델 호출 실패: 마지막 신호를 stale로만 알리고 새 신호는 저장하지 않는다")
    void modelFailurePublishesStale() {
        candles(15);
        latest("HOLD");
        given(modelClient.predict(any())).willThrow(new BusinessException(ErrorCode.MODEL_UNAVAILABLE));

        service.generate("KRW-BTC", T);

        verify(publisher).publishStaleSignal("KRW-BTC", "HOLD", new BigDecimal("0.5"));
        verify(publisher, never()).publishSignalChange(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("모델 호출 실패인데 마지막 신호도 없으면 아무것도 발행하지 않는다")
    void modelFailureWithoutLatest() {
        candles(15);
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC")).willReturn(Optional.empty());
        given(modelClient.predict(any())).willThrow(new BusinessException(ErrorCode.MODEL_UNAVAILABLE));

        service.generate("KRW-BTC", T);

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("모르는 라벨이나 확률 합이 0인 응답은 실패로 보고 stale 처리")
    void invalidResponseIsStale() {
        candles(15);
        latest("BUY");
        given(modelClient.predict(any())).willReturn(new ModelDecision("MAYBE", 0.3, 0.3, 0.4, 0.4),
                new ModelDecision("BUY", 0, 0, 0, 0.4));

        service.generate("KRW-BTC", T);
        service.generate("KRW-BTC", T);

        verify(publisher, org.mockito.Mockito.times(2)).publishStaleSignal(eq("KRW-BTC"), eq("BUY"), any());
        verify(publisher, never()).publishSignalChange(any(), any(), any(), any(), any(), anyBoolean());
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
