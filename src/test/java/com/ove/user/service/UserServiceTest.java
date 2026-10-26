package com.ove.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.user.domain.User;
import com.ove.user.dto.MyInfoResponse;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.repository.WatchlistRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WatchlistRepository watchlistRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void 내_정보_조회_성공시_통계는_0으로_내려간다() {
        UUID userId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-19T00:00:00Z");
        User user = User.builder().email("user@ove.com").password("hashed").nickname("오브").build();
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "createdAt", createdAt);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        MyInfoResponse response = userService.getMyInfo(userId);

        assertThat(response.getUser().getUserId()).isEqualTo(userId);
        assertThat(response.getUser().getEmail()).isEqualTo("user@ove.com");
        assertThat(response.getUser().getNickname()).isEqualTo("오브");
        assertThat(response.getUser().getCreatedAt()).isEqualTo(createdAt);
        assertThat(response.getStats().getWatchlistCount()).isZero();
        assertThat(response.getStats().getActiveApiKeyCount()).isZero();
    }

    @Test
    void 사용자가_없으면_USER_NOT_FOUND() {
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getMyInfo(userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
