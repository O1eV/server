package com.ove.user.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.user.dto.MyInfoResponse;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.repository.WatchlistRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final WatchlistRepository watchlistRepository;

    public MyInfoResponse getMyInfo(UUID userId) {
        return userRepository.findById(userId)
                .filter(user -> user.getStatus() == com.ove.user.domain.UserStatus.ACTIVE)
                .map(user -> MyInfoResponse.from(user, watchlistRepository.countByUserId(userId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
