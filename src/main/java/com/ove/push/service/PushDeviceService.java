package com.ove.push.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.push.domain.PushDevice;
import com.ove.push.dto.PushDeviceRequest;
import com.ove.push.dto.PushDeviceResponse;
import com.ove.push.repository.PushDeviceRepository;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PushDeviceService {

    private final PushDeviceRepository pushDeviceRepository;
    private final UserRepository userRepository;

    /**
     * 같은 토큰으로 다시 등록하면 플랫폼·알림 설정만 갱신한다.
     * 앱 재설치·로그인 기기 변경으로 토큰이 다른 사용자에게 넘어간 경우에는 이전 사용자의 알림을 받지 않도록 409로 거절한다.
     */
    @Transactional
    public PushDeviceResponse register(UUID userId, PushDeviceRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String pushToken = request.getPushToken().trim();

        return pushDeviceRepository.findByPushToken(pushToken)
                .map(existing -> updateExisting(userId, existing, request))
                .orElseGet(() -> saveNew(user, request, pushToken));
    }

    private PushDeviceResponse updateExisting(UUID userId, PushDevice existing, PushDeviceRequest request) {
        if (!existing.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.PUSH_TOKEN_ALREADY_REGISTERED);
        }
        existing.update(request.getPlatform(), request.getAlertEnabled());
        return PushDeviceResponse.from(existing);
    }

    private PushDeviceResponse saveNew(User user, PushDeviceRequest request, String pushToken) {
        try {
            PushDevice saved = pushDeviceRepository.saveAndFlush(PushDevice.builder()
                    .user(user)
                    .platform(request.getPlatform())
                    .pushToken(pushToken)
                    .alertEnabled(request.getAlertEnabled())
                    .build());
            return PushDeviceResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 토큰이 들어와 유일 제약에 걸린 경우
            throw new BusinessException(ErrorCode.PUSH_TOKEN_ALREADY_REGISTERED);
        }
    }
}
