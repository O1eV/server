package com.ove.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.market.service.MarketService;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.dto.WatchlistAddResponse;
import com.ove.watchlist.dto.WatchlistListResponse;
import com.ove.watchlist.repository.WatchlistRepository;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private WatchlistRepository watchlistRepository;

    @Mock
    private WatchlistWriteService writeService;

    @Mock
    private MarketService marketService;

    private WatchlistService watchlistService;

    @BeforeEach
    void setUp() {
        watchlistService = new WatchlistService(watchlistRepository, writeService, marketService);
    }

    @Test
    @DisplayName("추가: 소문자·공백이 섞여도 대문자로 정규화해 종목을 확인한 뒤 쓰기 서비스에 넘긴다")
    void addNormalizesSymbol() {
        WatchlistAddResponse expected = new WatchlistAddResponse(watchlist("KRW-BTC"));
        given(writeService.add(USER_ID, "KRW-BTC")).willReturn(expected);

        WatchlistAddResponse response = watchlistService.add(USER_ID, " krw-btc ");

        assertThat(response).isSameAs(expected);
        verify(marketService).getSupportedMarket("KRW-BTC");
    }

    @Test
    @DisplayName("추가: 지원하지 않는 종목이면 MARKET_NOT_FOUND이고 쓰기 서비스를 부르지 않는다")
    void addUnknownMarket() {
        given(marketService.getSupportedMarket("KRW-DOGE")).willThrow(new BusinessException(ErrorCode.MARKET_NOT_FOUND));

        assertThatThrownBy(() -> watchlistService.add(USER_ID, "krw-doge"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_NOT_FOUND));
        verifyNoInteractions(writeService);
    }

    @Test
    @DisplayName("추가: 업비트가 안 되면 UPSTREAM_UNAVAILABLE을 그대로 올리고 쓰기 서비스를 부르지 않는다")
    void addUpstreamFailure() {
        given(marketService.getSupportedMarket("KRW-BTC")).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        assertThatThrownBy(() -> watchlistService.add(USER_ID, "KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
        verifyNoInteractions(writeService);
    }

    @Test
    @DisplayName("삭제: 소문자로 와도 대문자로 정규화해 쓰기 서비스에 넘기고 업비트는 부르지 않는다")
    void removeNormalizes() {
        watchlistService.remove(USER_ID, "krw-btc");

        verify(writeService).remove(USER_ID, "KRW-BTC");
        verifyNoInteractions(marketService);
    }

    @Test
    @DisplayName("목록: 종목명·현재가를 채우고 signal·confidence는 null")
    void getWatchlist() {
        given(watchlistRepository.findByUserIdOrderByAddedAtAscIdAsc(USER_ID))
                .willReturn(List.of(watchlist("KRW-BTC"), watchlist("KRW-ETH")));
        given(marketService.getSupportedMarkets()).willReturn(List.of(
                UpbitMarketResponse.builder().market("KRW-BTC").koreanName("비트코인").englishName("Bitcoin").build(),
                UpbitMarketResponse.builder().market("KRW-ETH").koreanName("이더리움").englishName("Ethereum").build()));
        given(marketService.getSupportedTickers()).willReturn(List.of(
                UpbitTickerResponse.builder().market("KRW-BTC").tradePrice(new BigDecimal("100000000")).build(),
                UpbitTickerResponse.builder().market("KRW-ETH").tradePrice(new BigDecimal("4000000")).build()));

        WatchlistListResponse response = watchlistService.getWatchlist(USER_ID);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getSymbol()).isEqualTo("KRW-BTC");
        assertThat(response.getItems().get(0).getKoreanName()).isEqualTo("비트코인");
        assertThat(response.getItems().get(0).getPrice()).isEqualByComparingTo("100000000");
        assertThat(response.getItems().get(0).getSignal()).isNull();
        assertThat(response.getItems().get(0).getConfidence()).isNull();
        assertThat(response.getItems().get(1).getSymbol()).isEqualTo("KRW-ETH");
    }

    @Test
    @DisplayName("목록: 업비트가 실패하면 price·koreanName만 null이고 목록은 정상 반환")
    void getWatchlistUpstreamFailure() {
        given(watchlistRepository.findByUserIdOrderByAddedAtAscIdAsc(USER_ID)).willReturn(List.of(watchlist("KRW-BTC")));
        given(marketService.getSupportedMarkets()).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));
        given(marketService.getSupportedTickers()).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        WatchlistListResponse response = watchlistService.getWatchlist(USER_ID);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getSymbol()).isEqualTo("KRW-BTC");
        assertThat(response.getItems().get(0).getPrice()).isNull();
        assertThat(response.getItems().get(0).getKoreanName()).isNull();
    }

    @Test
    @DisplayName("목록: 비어 있으면 업비트를 부르지 않고 빈 목록")
    void getWatchlistEmpty() {
        given(watchlistRepository.findByUserIdOrderByAddedAtAscIdAsc(USER_ID)).willReturn(List.of());

        assertThat(watchlistService.getWatchlist(USER_ID).getItems()).isEmpty();
        verifyNoInteractions(marketService);
    }

    private Watchlist watchlist(String symbol) {
        return Watchlist.builder().userId(USER_ID).symbol(symbol).build();
    }
}
