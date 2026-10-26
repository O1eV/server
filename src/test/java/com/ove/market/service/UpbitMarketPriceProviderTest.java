package com.ove.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;

/**
 * 모의 주문 체결가 제공자가 업비트 호출을 UpbitClient 한 곳에 맡기고, 시세를 못 얻으면 임의 가격 없이 거절하는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class UpbitMarketPriceProviderTest {

    @Mock
    private UpbitClient upbitClient;

    @InjectMocks
    private UpbitMarketPriceProvider provider;

    @Test
    @DisplayName("거래소 종목 목록에 있으면 통과하고, 없으면 MARKET_NOT_FOUND(404)로 거절한다")
    void validateMarket() {
        given(upbitClient.getMarkets()).willReturn(List.of(market("KRW-BTC")));

        provider.validateMarket("KRW-BTC");

        assertThatThrownBy(() -> provider.validateMarket("KRW-ZZZZ"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_NOT_FOUND));
    }

    @Test
    @DisplayName("업비트 현재가(trade_price)를 체결가로 돌려준다")
    void returnsTradePrice() {
        given(upbitClient.getTickers(List.of("KRW-BTC"))).willReturn(List.of(ticker("KRW-BTC", "150000000.5")));

        assertThat(provider.getLatestPrice("KRW-BTC")).isEqualByComparingTo("150000000.5");
    }

    @Test
    @DisplayName("업비트 호출이 실패(UPSTREAM_UNAVAILABLE)하면 임의 가격 없이 MARKET_PRICE_UNAVAILABLE(503)로 거절한다")
    void upstreamFailureRejectsOrder() {
        given(upbitClient.getTickers(List.of("KRW-BTC"))).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        assertThatThrownBy(() -> provider.getLatestPrice("KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("응답이 비었거나, 여러 건이거나, 가격이 없거나 0 이하면 MARKET_PRICE_UNAVAILABLE")
    void invalidResponsesRejectOrder() {
        given(upbitClient.getTickers(List.of("KRW-AAA"))).willReturn(List.of());
        given(upbitClient.getTickers(List.of("KRW-BBB"))).willReturn(
                List.of(ticker("KRW-BBB", "1"), ticker("KRW-BBB", "2")));
        given(upbitClient.getTickers(List.of("KRW-CCC"))).willReturn(List.of(ticker("KRW-CCC", null)));
        given(upbitClient.getTickers(List.of("KRW-DDD"))).willReturn(List.of(ticker("KRW-DDD", "0")));
        given(upbitClient.getTickers(List.of("KRW-EEE"))).willReturn(List.of(ticker("KRW-EEE", "-5")));

        for (String symbol : new String[] {"KRW-AAA", "KRW-BBB", "KRW-CCC", "KRW-DDD", "KRW-EEE"}) {
            assertThatThrownBy(() -> provider.getLatestPrice(symbol))
                    .as("symbol=%s", symbol)
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));
        }
    }

    @Test
    @DisplayName("포트폴리오 시세는 상장폐지 종목을 건너뛰고 나머지 종목만 돌려준다")
    void portfolioPricesSkipUnavailableSymbols() {
        given(upbitClient.getMarkets()).willReturn(List.of(market("KRW-BTC"), market("KRW-ETH")));
        given(upbitClient.getTickers(List.of("KRW-BTC", "KRW-ETH"))).willReturn(List.of(
                ticker("KRW-BTC", "150000000.5"),
                ticker("KRW-ETH", null)));

        assertThat(provider.getPortfolioPrices(List.of("KRW-BTC", "KRW-ETH", "KRW-OLD")))
                .containsExactly(Map.entry("KRW-BTC", new BigDecimal("150000000.5")));
    }

    @Test
    @DisplayName("포트폴리오 시세 조회가 실패하면 예외 대신 빈 결과를 돌려준다")
    void portfolioPricesReturnEmptyOnFailure() {
        given(upbitClient.getMarkets()).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        assertThat(provider.getPortfolioPrices(List.of("KRW-BTC"))).isEmpty();
    }

    @Test
    @DisplayName("현재가 요청이 실패해도 포트폴리오 시세는 예외 대신 빈 결과를 돌려준다")
    void portfolioPricesReturnEmptyWhenTickersFail() {
        given(upbitClient.getMarkets()).willReturn(List.of(market("KRW-BTC")));
        given(upbitClient.getTickers(List.of("KRW-BTC")))
                .willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        assertThat(provider.getPortfolioPrices(List.of("KRW-BTC"))).isEmpty();
    }

    @Test
    @DisplayName("UPSTREAM_UNAVAILABLE가 아닌 다른 업무 예외는 그대로 올린다")
    void otherBusinessExceptionsPropagate() {
        given(upbitClient.getTickers(List.of("KRW-BTC"))).willThrow(new BusinessException(ErrorCode.INVALID_REQUEST));

        assertThatThrownBy(() -> provider.getLatestPrice("KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private UpbitMarketResponse market(String symbol) {
        return UpbitMarketResponse.builder().market(symbol).koreanName("테스트").englishName("Test").build();
    }

    private UpbitTickerResponse ticker(String market, String price) {
        return UpbitTickerResponse.builder()
                .market(market)
                .tradePrice(price == null ? null : new BigDecimal(price))
                .signedChangeRate(BigDecimal.ZERO)
                .build();
    }
}
