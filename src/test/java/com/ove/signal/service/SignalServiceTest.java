package com.ove.signal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.DemoStockService;
import com.ove.signal.domain.Signal;
import com.ove.signal.repository.SignalRepository;

/** 신호 저장 시 허용 종목: 코인, 그리고 키움 시연이 켜져 있을 때의 설정 주식 종목. */
@SuppressWarnings("unchecked")
class SignalServiceTest {

    SignalRepository repository = mock(SignalRepository.class);
    ObjectProvider<DemoStockService> demoStocks = mock(ObjectProvider.class);
    DemoStockService demoStockService = mock(DemoStockService.class);
    SignalService service;

    @BeforeEach
    void setUp() {
        service = new SignalService(repository, demoStocks);
    }

    private Signal signal(String symbol) {
        return new Signal(symbol, null, "BUY", new BigDecimal("0.7"), new BigDecimal("0.2"), new BigDecimal("0.1"),
                new BigDecimal("0.7"), null, Instant.parse("2026-10-26T00:00:00Z"), null);
    }

    @Test
    @DisplayName("코인 신호는 시연 설정과 관계없이 저장된다")
    void recordsCoin() {
        service.record(signal("KRW-BTC"));

        verify(repository).save(org.mockito.ArgumentMatchers.any(Signal.class));
    }

    @Test
    @DisplayName("키움 시연이 켜져 있고 설정에 있는 주식 종목 신호는 저장된다")
    void recordsConfiguredStock() {
        given(demoStocks.getIfAvailable()).willReturn(demoStockService);
        given(demoStockService.supportsSymbol("005930")).willReturn(true);

        service.record(signal("005930"));

        verify(repository).save(org.mockito.ArgumentMatchers.any(Signal.class));
    }

    @Test
    @DisplayName("키움 시연이 꺼져 있거나 설정에 없는 주식 종목은 MARKET_NOT_FOUND")
    void rejectsOtherStocks() {
        given(demoStocks.getIfAvailable()).willReturn(null);
        assertThatThrownBy(() -> service.record(signal("005930"))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_NOT_FOUND));

        given(demoStocks.getIfAvailable()).willReturn(demoStockService);
        given(demoStockService.supportsSymbol("000660")).willReturn(false);
        assertThatThrownBy(() -> service.record(signal("000660"))).isInstanceOf(BusinessException.class);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(Signal.class));
    }
}
