package com.ove.signal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.signal.domain.Signal;
import com.ove.signal.domain.SignalType;
import com.ove.signal.dto.SignalHistoryResponse;
import com.ove.signal.dto.SignalResponse;
import com.ove.signal.event.SignalChangedEvent;
import com.ove.signal.repository.SignalRepository;

@ExtendWith(MockitoExtension.class)
class SignalServiceTest {

    @Mock
    private SignalRepository signalRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SignalService signalService;

    @BeforeEach
    void setUp() {
        signalService = new SignalService(signalRepository, eventPublisher);
    }

    @Test
    @DisplayName("최신 신호를 확률·확신도와 함께 돌려주고, 2분이 지났으면 stale=true")
    void latestSignal() {
        Signal fresh = signal("KRW-BTC", SignalType.BUY, Instant.now());
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC"))
                .willReturn(Optional.of(fresh));

        SignalResponse response = signalService.getLatest("krw-btc");

        assertThat(response.getSymbol()).isEqualTo("KRW-BTC");
        assertThat(response.getSignal()).isEqualTo("BUY");
        assertThat(response.getConfidence()).isEqualByComparingTo("0.87");
        assertThat(response.isStale()).isFalse();

        Signal stale = signal("KRW-BTC", SignalType.HOLD, Instant.now().minus(Duration.ofMinutes(3)));
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC"))
                .willReturn(Optional.of(stale));

        assertThat(signalService.getLatest("KRW-BTC").isStale()).isTrue();
    }

    @Test
    @DisplayName("신호가 없거나 심볼 형식이 틀리면 SIGNAL_NOT_FOUND·MARKET_NOT_FOUND")
    void latestNotFound() {
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> signalService.getLatest("KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SIGNAL_NOT_FOUND));
        assertThatThrownBy(() -> signalService.getLatest("BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_NOT_FOUND));
    }

    @Test
    @DisplayName("이력은 최신순(id 보조 정렬)으로 전환 행만 조회하고 페이지 정보를 돌려준다")
    void history() {
        Signal changed = signal("KRW-BTC", SignalType.BUY, Instant.now());
        ReflectionTestUtils.setField(changed, "previousSignal", SignalType.HOLD);
        given(signalRepository.findAllBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
                eq("KRW-BTC"), any(Instant.class), any(Instant.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(changed), PageRequest.of(1, 10), 21));

        SignalHistoryResponse response = signalService.getHistory("krw-btc", null, null, 1, 10);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().getFirst().getPreviousSignal()).isEqualTo("HOLD");
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getTotalElements()).isEqualTo(21);
        assertThat(response.getTotalPages()).isEqualTo(3);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        then(signalRepository).should().findAllBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
                eq("KRW-BTC"), any(Instant.class), any(Instant.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().toList())
                .extracting(Sort.Order::getProperty)
                .containsExactly("generatedAt", "id");
    }

    @Test
    @DisplayName("기간을 생략하면 최근 7일, 90일을 넘거나 from이 to보다 늦으면 INVALID_REQUEST")
    void historyRangeValidation() {
        given(signalRepository.findAllBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
                eq("KRW-BTC"), any(Instant.class), any(Instant.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        signalService.getHistory("KRW-BTC", null, null, 0, 20);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        then(signalRepository).should().findAllBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
                eq("KRW-BTC"), fromCaptor.capture(), toCaptor.capture(), any(Pageable.class));
        assertThat(Duration.between(fromCaptor.getValue(), toCaptor.getValue())).isEqualTo(Duration.ofDays(7));

        Instant now = Instant.now();
        assertThatThrownBy(() -> signalService.getHistory("KRW-BTC", now.minus(Duration.ofDays(91)), now, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> signalService.getHistory("KRW-BTC", now, now.minusSeconds(1), 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> signalService.getHistory("KRW-BTC", null, null, -1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> signalService.getHistory("KRW-BTC", null, null, 0, 101))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("첫 신호는 previousSignal이 null이고 알림 이벤트를 내지 않는다")
    void firstSignalDoesNotNotify() {
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC"))
                .willReturn(Optional.empty());
        given(signalRepository.save(any(Signal.class))).willAnswer(invocation -> {
            Signal saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            ReflectionTestUtils.setField(saved, "generatedAt", Instant.parse("2026-10-26T00:00:00Z"));
            return saved;
        });

        Signal result = signalService.record("krw-btc", SignalType.BUY, new BigDecimal("0.7"),
                new BigDecimal("0.2"), new BigDecimal("0.1"), new BigDecimal("0.8"), new BigDecimal("1000"));

        assertThat(result.getPreviousSignal()).isNull();
        assertThat(result.getSymbol()).isEqualTo("KRW-BTC");
        then(eventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("같은 신호가 이어지면 previousSignal은 null이고 이벤트도 내지 않는다")
    void sameSignalDoesNotNotify() {
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC"))
                .willReturn(Optional.of(signal("KRW-BTC", SignalType.BUY, Instant.now())));
        given(signalRepository.save(any(Signal.class))).willAnswer(invocation -> invocation.getArgument(0));

        Signal result = signalService.record("KRW-BTC", SignalType.BUY, new BigDecimal("0.7"),
                new BigDecimal("0.2"), new BigDecimal("0.1"), new BigDecimal("0.8"), new BigDecimal("1000"));

        assertThat(result.getPreviousSignal()).isNull();
        then(eventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("신호가 바뀌면 previousSignal을 남기고, 커밋 후 알림이 쓸 이벤트를 발행한다")
    void changedSignalPublishesEvent() {
        given(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC"))
                .willReturn(Optional.of(signal("KRW-BTC", SignalType.HOLD, Instant.now())));
        given(signalRepository.save(any(Signal.class))).willAnswer(invocation -> {
            Signal saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 7L);
            ReflectionTestUtils.setField(saved, "generatedAt", Instant.parse("2026-10-26T00:00:00Z"));
            return saved;
        });

        signalService.record("KRW-BTC", SignalType.SELL, new BigDecimal("0.1"),
                new BigDecimal("0.2"), new BigDecimal("0.7"), new BigDecimal("0.9"), new BigDecimal("900"));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOfSatisfying(SignalChangedEvent.class, event -> {
            assertThat(event.signalId()).isEqualTo(7L);
            assertThat(event.symbol()).isEqualTo("KRW-BTC");
            assertThat(event.previousSignal()).isEqualTo(SignalType.HOLD);
            assertThat(event.signal()).isEqualTo(SignalType.SELL);
            assertThat(event.confidence()).isEqualByComparingTo("0.9");
            assertThat(event.generatedAt()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        });
    }

    @Test
    @DisplayName("확률·확신도는 0~1, 가격은 0보다 커야 한다")
    void recordValidation() {
        assertInvalidProbability(new BigDecimal("-0.1"), new BigDecimal("0.2"), new BigDecimal("0.1"),
                new BigDecimal("0.8"), new BigDecimal("1000"));
        assertInvalidProbability(BigDecimal.ONE.add(new BigDecimal("0.1")), new BigDecimal("0.2"),
                new BigDecimal("0.1"), new BigDecimal("0.8"), new BigDecimal("1000"));
        assertThatThrownBy(() -> signalService.record("KRW-BTC", SignalType.BUY, new BigDecimal("0.7"),
                new BigDecimal("0.2"), new BigDecimal("0.1"), new BigDecimal("0.8"), BigDecimal.ZERO))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> signalService.record("BTC", SignalType.BUY, new BigDecimal("0.7"),
                new BigDecimal("0.2"), new BigDecimal("0.1"), new BigDecimal("0.8"), new BigDecimal("1000")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_NOT_FOUND));
        assertThatThrownBy(() -> signalService.record("KRW-BTC", null, new BigDecimal("0.7"),
                new BigDecimal("0.2"), new BigDecimal("0.1"), new BigDecimal("0.8"), new BigDecimal("1000")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private void assertInvalidProbability(BigDecimal buy, BigDecimal hold, BigDecimal sell,
                                          BigDecimal confidence, BigDecimal price) {
        assertThatThrownBy(() -> signalService.record("KRW-BTC", SignalType.BUY, buy, hold, sell,
                confidence, price))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private Signal signal(String symbol, SignalType type, Instant generatedAt) {
        Signal signal = Signal.builder()
                .symbol(symbol)
                .signal(type)
                .buyProbability(new BigDecimal("0.7"))
                .holdProbability(new BigDecimal("0.2"))
                .sellProbability(new BigDecimal("0.1"))
                .confidence(new BigDecimal("0.87"))
                .price(new BigDecimal("1000"))
                .generatedAt(generatedAt)
                .build();
        ReflectionTestUtils.setField(signal, "id", 1L);
        return signal;
    }
}
