package com.ove.signal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.signal.repository.SignalRepository;

@ExtendWith(MockitoExtension.class)
class SignalServiceTest {

    @Mock
    private SignalRepository repository;
    @InjectMocks
    private SignalService service;

    private void stubRepository() {
        given(repository.findBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of()));
    }

    @Test
    @DisplayName("기간을 생략하면 최근 7일, 페이지는 최신순 정렬로 조회하고 심볼은 대문자로 바꾼다")
    void defaultsToLastSevenDays() {
        stubRepository();
        Instant before = Instant.now();

        service.history("krw-btc", null, null, 0, 20);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
                eq("KRW-BTC"), from.capture(), to.capture(), pageable.capture());
        assertThat(to.getValue()).isAfterOrEqualTo(before);
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofDays(7));
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().toString()).isEqualTo("generatedAt: DESC,id: DESC");
    }

    @Test
    @DisplayName("from·to·page·size를 그대로 전달하고 페이지 정보를 응답에 담는다")
    void passesRangeAndPage() {
        Instant to = Instant.parse("2026-10-26T00:00:00Z");
        Instant from = to.minus(Duration.ofDays(90)); // 정확히 90일은 허용
        given(repository.findBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(any(), any(), any(), any()))
                .willAnswer(inv -> new PageImpl<>(List.of(), inv.<Pageable>getArgument(3), 45));

        var response = service.history("KRW-ETH", from, to, 2, 10);

        org.mockito.Mockito.verify(repository).findBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
                eq("KRW-ETH"), eq(from), eq(to), any());
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(45);
        assertThat(response.totalPages()).isEqualTo(5);
    }

    @Test
    @DisplayName("조회 기간이 90일을 넘으면 INVALID_REQUEST")
    void rejectsRangeOver90Days() {
        Instant to = Instant.parse("2026-10-26T00:00:00Z");

        assertThatThrownBy(() -> service.history("KRW-BTC", to.minus(Duration.ofDays(90)).minusSeconds(1), to, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("from이 to보다 늦으면 INVALID_REQUEST")
    void rejectsFromAfterTo() {
        Instant to = Instant.parse("2026-10-26T00:00:00Z");

        assertThatThrownBy(() -> service.history("KRW-BTC", to.plusSeconds(1), to, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("page가 음수이거나 size가 1~100을 벗어나면 INVALID_REQUEST")
    void rejectsInvalidPaging() {
        for (int[] paging : new int[][] {{-1, 20}, {0, 0}, {0, 101}}) {
            assertThatThrownBy(() -> service.history("KRW-BTC", null, null, paging[0], paging[1]))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    @Test
    @DisplayName("지원하지 않는 종목은 MARKET_NOT_FOUND")
    void rejectsUnsupportedSymbol() {
        assertThatThrownBy(() -> service.history("UNKNOWN", null, null, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_NOT_FOUND));
    }
}
