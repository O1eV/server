package com.ove.watchlist;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.MarketService;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.repository.WatchlistRepository;
import com.ove.watchlist.service.WatchlistWriteService;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {
    @Mock WatchlistRepository watchlist;
    @Mock UserRepository users;
    @Mock MarketService markets;
    WatchlistWriteService service;

    @Test
    void 사용자_행을_잠근_뒤_20개_상한을_확인한다() {
        UUID userId = UUID.randomUUID();
        service = new WatchlistWriteService(watchlist, users, 20);
        given(users.findByIdForUpdate(userId)).willReturn(Optional.of(User.builder().build()));
        given(watchlist.countByUserId(userId)).willReturn(20L);
        assertThatThrownBy(() -> service.add(userId, "KRW-BTC"))
            .isInstanceOf(BusinessException.class).extracting("errorCode")
            .isEqualTo(ErrorCode.WATCHLIST_CONFLICT);
        verify(users).findByIdForUpdate(userId);
        verify(watchlist, never()).saveAndFlush(any());
    }
}
