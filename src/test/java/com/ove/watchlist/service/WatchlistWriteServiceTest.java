package com.ove.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
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
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.dto.WatchlistAddResponse;
import com.ove.watchlist.repository.WatchlistRepository;

@ExtendWith(MockitoExtension.class)
class WatchlistWriteServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final int MAX_SIZE = 3;

    @Mock
    private WatchlistRepository watchlistRepository;

    @Mock
    private UserRepository userRepository;

    private WatchlistWriteService writeService;

    @BeforeEach
    void setUp() {
        writeService = new WatchlistWriteService(watchlistRepository, userRepository, MAX_SIZE);
    }

    @Test
    @DisplayName("추가: 저장하고 addedAt을 돌려준다")
    void add() {
        givenUserExists();
        given(watchlistRepository.saveAndFlush(any(Watchlist.class))).willAnswer(invocation -> {
            Watchlist saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "addedAt", Instant.parse("2026-10-26T01:00:00Z"));
            return saved;
        });

        WatchlistAddResponse response = writeService.add(USER_ID, "KRW-BTC");

        assertThat(response.getSymbol()).isEqualTo("KRW-BTC");
        assertThat(response.getAddedAt()).isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
        ArgumentCaptor<Watchlist> captor = ArgumentCaptor.forClass(Watchlist.class);
        verify(watchlistRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("추가: 이미 등록된 종목이면 WATCHLIST_CONFLICT와 중복 메시지")
    void addDuplicate() {
        givenUserExists();
        given(watchlistRepository.existsByUserIdAndSymbol(USER_ID, "KRW-BTC")).willReturn(true);

        assertConflict("이미 관심 종목에 있습니다.");
        verify(watchlistRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("추가: 최대 개수에 도달하면 WATCHLIST_CONFLICT와 최대 개수 메시지, 직전까지는 추가된다")
    void addMaxSize() {
        givenUserExists();
        given(watchlistRepository.countByUserId(USER_ID)).willReturn((long) MAX_SIZE);

        assertConflict("관심 종목은 최대 3개까지 등록할 수 있습니다.");
        verify(watchlistRepository, never()).saveAndFlush(any());

        given(watchlistRepository.countByUserId(USER_ID)).willReturn((long) MAX_SIZE - 1);
        given(watchlistRepository.saveAndFlush(any(Watchlist.class))).willAnswer(i -> i.getArgument(0));
        assertThat(writeService.add(USER_ID, "KRW-BTC").getSymbol()).isEqualTo("KRW-BTC");
    }

    @Test
    @DisplayName("추가: 유일 제약 위반(동시 요청)은 중복 메시지의 WATCHLIST_CONFLICT로 바뀐다")
    void addUniqueViolation() {
        givenUserExists();
        given(watchlistRepository.saveAndFlush(any(Watchlist.class))).willThrow(new DataIntegrityViolationException("dup"));

        assertConflict("이미 관심 종목에 있습니다.");
    }

    @Test
    @DisplayName("추가: 회원이 없으면 USER_NOT_FOUND")
    void addUserMissing() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writeService.add(USER_ID, "KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("삭제: 한 건 지우면 정상 종료")
    void remove() {
        given(watchlistRepository.deleteByUserIdAndSymbol(USER_ID, "KRW-BTC")).willReturn(1);

        writeService.remove(USER_ID, "KRW-BTC");

        verify(watchlistRepository).deleteByUserIdAndSymbol(USER_ID, "KRW-BTC");
    }

    @Test
    @DisplayName("삭제: 지운 게 0건이면 WATCHLIST_NOT_FOUND")
    void removeMissing() {
        given(watchlistRepository.deleteByUserIdAndSymbol(USER_ID, "KRW-BTC")).willReturn(0);

        assertThatThrownBy(() -> writeService.remove(USER_ID, "KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND));
    }

    private void givenUserExists() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(User.builder().build()));
    }

    private void assertConflict(String message) {
        assertThatThrownBy(() -> writeService.add(USER_ID, "KRW-BTC"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WATCHLIST_CONFLICT);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }
}
