package com.ove.paper.service;

import java.util.List;

import com.ove.account.domain.PaperAccount;
import com.ove.paper.domain.PaperHolding;

/**
 * 포트폴리오 응답을 만들 때 같은 시점의 계좌와 보유 종목을 함께 담는다.
 * 시세 조회는 이 스냅샷을 읽은 트랜잭션 밖에서 한다.
 */
public record PaperPortfolioSnapshot(PaperAccount account, List<PaperHolding> holdings) {
}
