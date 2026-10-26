package com.ove.paper.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.paper.domain.PaperHolding;
import com.ove.paper.repository.PaperHoldingRepository;

import lombok.RequiredArgsConstructor;

/**
 * 포트폴리오 조회에 필요한 DB 읽기만 짧은 트랜잭션으로 묶는다.
 * 계좌와 보유 종목을 같은 시점에 읽어야 현금만 체결 후, 수량은 체결 전인 응답이 나가지 않는다.
 * 업비트 시세 조회는 이 빈을 호출한 뒤 트랜잭션 밖에서 한다.
 */
@Service
@RequiredArgsConstructor
public class PaperTradingReadService {

    private final PaperAccountRepository paperAccountRepository;
    private final PaperHoldingRepository paperHoldingRepository;

    @Transactional(readOnly = true)
    public PaperPortfolioSnapshot getPortfolioSnapshot(UUID userId) {
        PaperAccount account = paperAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_ACCOUNT_NOT_FOUND));
        List<PaperHolding> holdings =
                paperHoldingRepository.findAllByPaperAccountUserIdOrderBySymbolAsc(userId);
        return new PaperPortfolioSnapshot(account, holdings);
    }
}
