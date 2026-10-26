package com.ove.watchlist.service;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.dto.WatchlistAddResponse;
import com.ove.watchlist.repository.WatchlistRepository;

/**
 * 관심 종목 쓰기(저장·삭제)만 트랜잭션으로 묶는다.
 * 업비트를 부르는 일은 WatchlistService가 이 빈을 부르기 전에 끝내서, 업비트가 느릴 때 DB 커넥션을 잡고 있지 않는다.
 * 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 안 걸리므로, 일부러 별도 빈으로 나눴다.
 */
@Service
public class WatchlistWriteService {

    private final WatchlistRepository watchlistRepository;
    private final UserRepository userRepository;
    private final int maxSize;

    public WatchlistWriteService(WatchlistRepository watchlistRepository, UserRepository userRepository,
                                 @Value("${watchlist.max-size:20}") int maxSize) {
        this.watchlistRepository = watchlistRepository;
        this.userRepository = userRepository;
        this.maxSize = maxSize;
    }

    // 중복·최대 개수 확인과 저장을 한 트랜잭션으로 처리할 때 사용 (symbol은 정규화·검증이 끝난 값)
    // 같은 회원의 추가 요청이 동시에 와도 확인이 깨지지 않도록 회원 행에 쓰기 잠금을 걸어 한 줄로 세운다.
    // (유일 제약만으로는 같은 종목 중복만 막을 뿐 "최대 개수" 경쟁은 못 막고, 회원별 잠금은 다른 회원끼리 영향이 없다.)
    // 유일 제약은 마지막 안전망이라, 그래도 터지면 중복으로 보고 WATCHLIST_CONFLICT로 바꿔 준다.
    @Transactional
    public WatchlistAddResponse add(UUID userId, String symbol) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (watchlistRepository.existsByUserIdAndSymbol(userId, symbol)) {
            throw new BusinessException(ErrorCode.WATCHLIST_CONFLICT, DUPLICATE_MESSAGE);
        }
        if (watchlistRepository.countByUserId(userId) >= maxSize) {
            throw new BusinessException(ErrorCode.WATCHLIST_CONFLICT,
                    "관심 종목은 최대 " + maxSize + "개까지 등록할 수 있습니다.");
        }

        try {
            return new WatchlistAddResponse(watchlistRepository.saveAndFlush(
                    Watchlist.builder().userId(userId).symbol(symbol).build()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.WATCHLIST_CONFLICT, DUPLICATE_MESSAGE);
        }
    }

    // 한 번의 delete 쿼리로 지우고, 지운 게 없으면 WATCHLIST_NOT_FOUND를 던질 때 사용 (동시에 두 번 지워도 500이 나지 않는다)
    @Transactional
    public void remove(UUID userId, String symbol) {
        if (watchlistRepository.deleteByUserIdAndSymbol(userId, symbol) == 0) {
            throw new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND);
        }
    }

    private static final String DUPLICATE_MESSAGE = "이미 관심 종목에 있습니다.";
}
