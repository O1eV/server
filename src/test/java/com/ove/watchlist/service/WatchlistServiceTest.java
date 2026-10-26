package com.ove.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.market.service.MarketService;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.dto.WatchlistAddResponse;
import com.ove.watchlist.dto.WatchlistListResponse;
import com.ove.watchlist.repository.WatchlistRepository;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final int MAX_SIZE = 3;

    @Mock
    private WatchlistRepository watchlistRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MarketService marketService;

    private WatchlistService watchlistService;

    @BeforeEach
    void setUp() {
        watchlistService = new WatchlistService(watchlistRepository, userRepository, marketService, MAX_SIZE);
    }

    @Test
    @DisplayName("추가: 소문자·공백이 섞여도 대문자로 정규화해 저장하고 addedAt을 돌려준다")
    void addNormalizesSymbol() {
        givenUserExists();
        given(watchlistRepository.saveAndFlush(any(Watchlist.class))).willAnswer(invocation -> {
            Watchlist saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "addedAt", Instant.parse("2026-10-26T01:00:00Z"));
            return saved;
        });

        WatchlistAddResponse response = watchlistService.add(USER_ID, " krw-btc ");

        assertThat(response.getSymbol()).isEqualTo("KRW-BTC");
        assertThat(response.getAddedAt()).isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
        ArgumentCaptor<Watchlist> captor = ArgumentCaptor.forClass(Watchlist.class);
        verify(watchlistRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getSymbol()).isEqualTo("KRW-BTC");
        verify(marketService).getSupportedMarket("KRW-BTC");
    }

    @Test
    @DisplayName("추가: 지원하지 않는 종목이면 MARKET_NOT_FOUND이고 저장하지 않는다")
    void addUnknownMarket() {
        given(marketService.getSupportedMarket("KRW-DOGE")).willThrow(new BusinessException(ErrorCode.MARKET_NOT_FOUND));

        assertThatThrownBy(() -> watchlistService.add(USER_ID, "krw-doge"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_NOT_FOUND));
        verifyNoInteractions(watchlistRepository, userRepository);
    }

    @Test
    @DisplayName("추가: 업비트가 안 되면 UPSTREAM_UNAVAILABLE을 그대로 올린다")
    void addUpstreamFailure() {
        given(marketService.getSupportedMarket("KRW-BTC")).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        assertThatThrownBy(() -> watchlistService.add(USER_ID, "KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
        verifyNoInteractions(watchlistRepository);
    }

    @Test
    @DisplayName("추가: 이미 등록된 종목이면 WATCHLIST_CONFLICT")
    void addDuplicate() {
        givenUserExists();
        given(watchlistRepository.existsByUserIdAndSymbol(USER_ID, "KRW-BTC")).willReturn(true);

        assertConflict(() -> watchlistService.add(USER_ID, "KRW-BTC"));
        verify(watchlistRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("추가: 최대 개수에 도달하면 WATCHLIST_CONFLICT, 최대 개수 직전까지는 추가된다")
    void addMaxSize() {
        givenUserExists();
        given(watchlistRepository.countByUserId(USER_ID)).willReturn((long) MAX_SIZE);

        assertConflict(() -> watchlistService.add(USER_ID, "KRW-BTC"));
        verify(watchlistRepository, never()).saveAndFlush(any());

        given(watchlistRepository.countByUserId(USER_ID)).willReturn((long) MAX_SIZE - 1);
        given(watchlistRepository.saveAndFlush(any(Watchlist.class))).willAnswer(i -> i.getArgument(0));
        assertThat(watchlistService.add(USER_ID, "KRW-BTC").getSymbol()).isEqualTo("KRW-BTC");
    }

    @Test
    @DisplayName("추가: 유일 제약 위반(동시 요청)은 WATCHLIST_CONFLICT로 바뀐다")
    void addUniqueViolation() {
        givenUserExists();
        given(watchlistRepository.saveAndFlush(any(Watchlist.class))).willThrow(new DataIntegrityViolationException("dup"));

        assertConflict(() -> watchlistService.add(USER_ID, "KRW-BTC"));
    }

    @Test
    @DisplayName("추가: 회원이 없으면 USER_NOT_FOUND")
    void addUserMissing() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> watchlistService.add(USER_ID, "KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("삭제: 소문자로 와도 대문자로 정규화해 지운다")
    void removeNormalizes() {
        Watchlist watchlist = watchlist("KRW-BTC");
        given(watchlistRepository.findByUserIdAndSymbol(USER_ID, "KRW-BTC")).willReturn(Optional.of(watchlist));

        watchlistService.remove(USER_ID, "krw-btc");

        verify(watchlistRepository).delete(watchlist);
    }

    @Test
    @DisplayName("삭제: 목록에 없으면 WATCHLIST_NOT_FOUND")
    void removeMissing() {
        given(watchlistRepository.findByUserIdAndSymbol(USER_ID, "KRW-BTC")).willReturn(Optional.empty());

        assertThatThrownBy(() -> watchlistService.remove(USER_ID, "KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND));
        verify(watchlistRepository, never()).delete(any());
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

    private void givenUserExists() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(User.builder().build()));
    }

    private Watchlist watchlist(String symbol) {
        return Watchlist.builder().userId(USER_ID).symbol(symbol).build();
    }

    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WATCHLIST_CONFLICT));
    }
}
