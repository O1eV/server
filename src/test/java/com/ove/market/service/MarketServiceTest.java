package com.ove.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitCandleResponse;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.market.dto.CandleListResponse;
import com.ove.market.dto.CandleResponse;
import com.ove.market.dto.MarketListResponse;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    private static final Instant LATEST = Instant.parse("2026-10-19T12:00:00Z");

    @Mock
    private UpbitClient upbitClient;

    @InjectMocks
    private MarketService marketService;

    @Test
    @DisplayName("page * size가 int 범위를 넘어도 오버플로 없이 빈 페이지를 돌려준다")
    void pageOverflow() {
        stubMarkets();

        MarketListResponse response = marketService.getMarkets(null, null, null, Integer.MAX_VALUE, 100);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("size가 0이거나 100을 넘으면 업비트를 부르기 전에 INVALID_REQUEST")
    void invalidSize() {
        assertInvalidRequest(() -> marketService.getMarkets(null, null, null, 0, 0));
        assertInvalidRequest(() -> marketService.getMarkets(null, null, null, 0, 101));
        assertInvalidRequest(() -> marketService.getMarkets(null, null, null, -1, 20));
        verifyNoInteractions(upbitClient);
    }

    @Test
    @DisplayName("size 상한(100)은 허용한다")
    void maxSize() {
        stubMarkets();

        MarketListResponse response = marketService.getMarkets(null, null, null, 0, 100);

        assertThat(response.getItems()).hasSize(3);
    }

    @Test
    @DisplayName("검색 limit이 1 미만이거나 50을 넘으면 INVALID_REQUEST")
    void invalidSearchLimit() {
        assertInvalidRequest(() -> marketService.searchMarkets("btc", 0));
        assertInvalidRequest(() -> marketService.searchMarkets("btc", -1));
        assertInvalidRequest(() -> marketService.searchMarkets("btc", 51));
        verifyNoInteractions(upbitClient);
    }

    @Test
    @DisplayName("limit이 200을 넘으면 200씩 나눠 부르고, cursor가 이전 묶음의 마지막 시각으로 이어진다")
    void candlesAreFetchedInBatchesWithoutDuplicates() {
        List<UpbitCandleResponse> first = candles(LATEST, 200);
        List<UpbitCandleResponse> second = candles(first.get(199).toInstant().minusSeconds(60), 200);
        List<UpbitCandleResponse> third = candles(second.get(199).toInstant().minusSeconds(60), 50);
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), isNull(), eq(200))).willReturn(first);
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(first.get(199).toInstant()), eq(200)))
                .willReturn(second);
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(second.get(199).toInstant()), eq(50)))
                .willReturn(third);

        CandleListResponse response = marketService.getCandles("KRW-BTC", "1m", null, null, 450);

        List<Instant> times = response.getCandles().stream().map(CandleResponse::getTime).toList();
        assertThat(times).hasSize(450);
        assertThat(new HashSet<>(times)).hasSize(450);
        assertThat(times).isSorted();
        assertThat(times.get(449)).isEqualTo(LATEST);

        ArgumentCaptor<Instant> cursors = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Integer> counts = ArgumentCaptor.forClass(Integer.class);
        verify(upbitClient, times(3)).getCandles(eq("minutes/1"), eq("KRW-BTC"), cursors.capture(),
                counts.capture());
        assertThat(cursors.getAllValues()).containsExactly(null, first.get(199).toInstant(),
                second.get(199).toInstant());
        assertThat(counts.getAllValues()).containsExactly(200, 200, 50);
    }

    @Test
    @DisplayName("업비트가 요청한 개수보다 적게 주면 거기서 멈춘다")
    void stopsWhenUpbitHasNoMoreCandles() {
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), isNull(), eq(200)))
                .willReturn(candles(LATEST, 120));

        CandleListResponse response = marketService.getCandles("KRW-BTC", "1m", null, null, 300);

        assertThat(response.getCandles()).hasSize(120);
        verify(upbitClient, times(1)).getCandles(anyString(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("from보다 오래된 캔들을 만나면 더 받지 않고 멈춘다")
    void stopsAtFrom() {
        Instant from = LATEST.minusSeconds(150 * 60);
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), isNull(), eq(200)))
                .willReturn(candles(LATEST, 200));

        CandleListResponse response = marketService.getCandles("KRW-BTC", "1m", from, null, 450);

        List<Instant> times = response.getCandles().stream().map(CandleResponse::getTime).toList();
        assertThat(times).hasSize(151);
        assertThat(times.get(0)).isEqualTo(from);
        assertThat(times.get(150)).isEqualTo(LATEST);
        // 첫 묶음 안에서 from을 지났으므로 두 번째 호출은 없어야 한다.
        verify(upbitClient, times(1)).getCandles(anyString(), anyString(), any(), anyInt());
        verifyNoMoreInteractions(upbitClient);
    }

    @Test
    @DisplayName("from이 to보다 늦으면 업비트를 부르기 전에 INVALID_REQUEST")
    void fromAfterTo() {
        assertInvalidRequest(() -> marketService.getCandles("KRW-BTC", "1m", LATEST, LATEST.minusSeconds(60), 10));
        verifyNoInteractions(upbitClient);
    }

    @Test
    @DisplayName("interval·limit이 잘못되면 업비트를 부르기 전에 INVALID_REQUEST")
    void invalidIntervalAndLimit() {
        assertInvalidRequest(() -> marketService.getCandles("KRW-BTC", "3m", null, null, 10));
        assertInvalidRequest(() -> marketService.getCandles("KRW-BTC", "1m", null, null, 0));
        assertInvalidRequest(() -> marketService.getCandles("KRW-BTC", "1m", null, null, 501));
        verifyNoInteractions(upbitClient);
    }

    @Test
    @DisplayName("업비트 캔들 시각 형식이 이상하면 500이 아니라 UPSTREAM_UNAVAILABLE")
    void brokenCandleTime() {
        given(upbitClient.getCandles(anyString(), anyString(), any(), anyInt()))
                .willReturn(List.of(candle("not-a-time")));

        assertThatThrownBy(() -> marketService.getCandles("KRW-BTC", "1m", null, null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("업비트 호출 실패(UPSTREAM_UNAVAILABLE)는 그대로 올라간다")
    void upstreamFailurePropagates() {
        given(upbitClient.getMarkets()).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        assertThatThrownBy(() -> marketService.getMarkets(null, null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private void assertInvalidRequest(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private void stubMarkets() {
        given(upbitClient.getMarkets()).willReturn(List.of(
                UpbitMarketResponse.builder().market("KRW-BTC").koreanName("비트코인").englishName("Bitcoin").build(),
                UpbitMarketResponse.builder().market("KRW-ETH").koreanName("이더리움").englishName("Ethereum").build(),
                UpbitMarketResponse.builder().market("KRW-XRP").koreanName("엑스알피").englishName("XRP").build()));
        given(upbitClient.getTickers(anyList())).willReturn(List.of(
                ticker("KRW-BTC"), ticker("KRW-ETH"), ticker("KRW-XRP")));
    }

    private UpbitTickerResponse ticker(String code) {
        return UpbitTickerResponse.builder()
                .market(code)
                .tradePrice(new BigDecimal("1000"))
                .signedChangeRate(new BigDecimal("0.01"))
                .build();
    }

    private UpbitCandleResponse candle(String timeUtc) {
        return UpbitCandleResponse.builder()
                .candleDateTimeUtc(timeUtc)
                .openingPrice(BigDecimal.ONE)
                .highPrice(BigDecimal.ONE)
                .lowPrice(BigDecimal.ONE)
                .tradePrice(BigDecimal.ONE)
                .candleAccTradeVolume(BigDecimal.ONE)
                .build();
    }

    // latest부터 1분씩 과거로 가는 가짜 캔들 묶음 (업비트처럼 최신순)
    private List<UpbitCandleResponse> candles(Instant latest, int count) {
        List<UpbitCandleResponse> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String time = latest.minusSeconds(i * 60L).toString();
            result.add(candle(time.substring(0, time.length() - 1)));
        }
        return result;
    }
}
