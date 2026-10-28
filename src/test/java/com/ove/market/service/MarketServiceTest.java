package com.ove.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
import java.util.Map;

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
import com.ove.market.dto.MarketResponse;
import com.ove.signal.dto.SignalSummary;
import com.ove.signal.service.SignalService;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    private static final Instant LATEST = Instant.parse("2026-10-19T12:00:00Z");

    @Mock
    private UpbitClient upbitClient;

    @Mock
    private SignalService signalService;

    @InjectMocks
    private MarketService marketService;

    @Test
    @DisplayName("page * size가 int 범위를 넘어도 오버플로 없이 빈 페이지를 돌려준다")
    void pageOverflow() {
        stubMarkets();

        MarketListResponse response = marketService.getMarkets(null, null, null, Integer.MAX_VALUE, 100, false);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("size가 0이거나 100을 넘으면 업비트를 부르기 전에 INVALID_REQUEST")
    void invalidSize() {
        assertInvalidRequest(() -> marketService.getMarkets(null, null, null, 0, 0, false));
        assertInvalidRequest(() -> marketService.getMarkets(null, null, null, 0, 101, false));
        assertInvalidRequest(() -> marketService.getMarkets(null, null, null, -1, 20, false));
        verifyNoInteractions(upbitClient);
    }

    @Test
    @DisplayName("size 상한(100)은 허용한다")
    void maxSize() {
        stubMarkets();

        MarketListResponse response = marketService.getMarkets(null, null, null, 0, 100, false);

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

        assertThatThrownBy(() -> marketService.getMarkets(null, null, null, 0, 20, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("비로그인 조회는 신호를 조회하지 않고 signal·confidence를 null로 둔다")
    void anonymousDoesNotMergeSignals() {
        stubMarkets();

        MarketListResponse response = marketService.getMarkets(null, null, null, 0, 20, false);

        assertThat(response.getItems()).allSatisfy(item -> {
            assertThat(item.getSignal()).isNull();
            assertThat(item.getConfidence()).isNull();
        });
        verifyNoInteractions(signalService);
    }

    @Test
    @DisplayName("비로그인 상태에서 signal 필터를 쓰면 업비트를 부르기 전에 UNAUTHORIZED")
    void signalFilterRequiresLogin() {
        assertThatThrownBy(() -> marketService.getMarkets(null, null, "BUY", 0, 20, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(upbitClient);
        verifyNoInteractions(signalService);
    }

    @Test
    @DisplayName("로그인하면 종목별 최신 신호·확신도를 합치고, 신호가 없는 종목은 null로 둔다")
    void mergesLatestSignalsForLoggedInUser() {
        stubMarkets();
        given(signalService.latestSummaries(anyList())).willReturn(Map.of(
                "KRW-BTC", new SignalSummary("BUY", new BigDecimal("0.71")),
                "KRW-ETH", new SignalSummary("HOLD", new BigDecimal("0.55"))));

        MarketListResponse response = marketService.getMarkets(null, null, null, 0, 20, true);

        assertThat(response.getItems())
                .extracting(MarketResponse::getSymbol, MarketResponse::getSignal, MarketResponse::getConfidence)
                .containsExactly(
                        tuple("KRW-BTC", "BUY", new BigDecimal("0.71")),
                        tuple("KRW-ETH", "HOLD", new BigDecimal("0.55")),
                        tuple("KRW-XRP", null, null));
    }

    @Test
    @DisplayName("로그인 사용자의 signal 필터는 최신 신호가 일치하는 종목만 남기고, 그 뒤에 페이지를 자른다")
    void signalFilterUsesLatestSignalsBeforePagination() {
        stubMarkets();
        given(signalService.latestSummaries(anyList())).willReturn(Map.of(
                "KRW-BTC", new SignalSummary("BUY", new BigDecimal("0.71")),
                "KRW-ETH", new SignalSummary("BUY", new BigDecimal("0.62")),
                "KRW-XRP", new SignalSummary("HOLD", new BigDecimal("0.51"))));

        MarketListResponse first = marketService.getMarkets(null, null, "BUY", 0, 1, true);
        MarketListResponse second = marketService.getMarkets(null, null, "BUY", 1, 1, true);

        assertThat(first.getItems()).extracting(MarketResponse::getSymbol).containsExactly("KRW-BTC");
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getItems()).extracting(MarketResponse::getSymbol).containsExactly("KRW-ETH");
        assertThat(second.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("signal 필터에 맞는 최신 신호가 없으면 빈 목록을 돌려준다")
    void signalFilterWithoutMatch() {
        stubMarkets();
        given(signalService.latestSummaries(anyList())).willReturn(Map.of(
                "KRW-BTC", new SignalSummary("HOLD", new BigDecimal("0.71"))));

        MarketListResponse response = marketService.getMarkets(null, null, "SELL", 0, 20, true);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("필터 값은 앞뒤 공백과 대소문자를 무시한다")
    void filtersAreNormalized() {
        stubMarkets();
        given(signalService.latestSummaries(anyList())).willReturn(Map.of(
                "KRW-BTC", new SignalSummary("BUY", new BigDecimal("0.71")),
                "KRW-ETH", new SignalSummary("HOLD", new BigDecimal("0.55")),
                "KRW-XRP", new SignalSummary("HOLD", new BigDecimal("0.51"))));

        MarketListResponse response = marketService.getMarkets(" coin ", " up ", " buy ", 0, 20, true);

        assertThat(response.getItems()).extracting(MarketResponse::getSymbol).containsExactly("KRW-BTC");
        assertThat(response.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("정해진 값이 아닌 필터는 업비트를 부르기 전에 INVALID_REQUEST")
    void invalidFilter() {
        assertInvalidRequest(() -> marketService.getMarkets("STOCK", null, null, 0, 20, false));
        assertInvalidRequest(() -> marketService.getMarkets(null, "RISE", null, 0, 20, false));
        assertInvalidRequest(() -> marketService.getMarkets(null, null, "MAYBE", 0, 20, true));
        // 공백만 있는 값은 정규화하면 빈 문자열이라 유효하지 않다.
        assertInvalidRequest(() -> marketService.getMarkets("   ", null, null, 0, 20, false));
        verifyNoInteractions(upbitClient);
        verifyNoInteractions(signalService);
    }

    @Test
    @DisplayName("업비트 현재가에 변동률이나 현재가가 없으면 500이 아니라 UPSTREAM_UNAVAILABLE")
    void incompleteTickerIsUpstreamUnavailable() {
        given(upbitClient.getMarkets()).willReturn(List.of(
                UpbitMarketResponse.builder().market("KRW-BTC").koreanName("비트코인").englishName("Bitcoin").build()));
        given(upbitClient.getTickers(anyList())).willReturn(List.of(
                UpbitTickerResponse.builder()
                        .market("KRW-BTC")
                        .tradePrice(new BigDecimal("1000"))
                        .build()));

        assertThatThrownBy(() -> marketService.getMarkets(null, null, null, 0, 20, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));

        given(upbitClient.getTickers(anyList())).willReturn(List.of(
                UpbitTickerResponse.builder()
                        .market("KRW-BTC")
                        .signedChangeRate(new BigDecimal("0.01"))
                        .build()));

        assertThatThrownBy(() -> marketService.getMarkets(null, null, null, 0, 20, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
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
