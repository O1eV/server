package com.ove.paper.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.paper.domain.OrderSide;
import com.ove.paper.domain.PaperHolding;
import com.ove.paper.domain.PaperOrder;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;

@DataJpaTest
class PaperTradingRepositoryTest {

    @Autowired
    private PaperAccountRepository paperAccountRepository;

    @Autowired
    private PaperHoldingRepository paperHoldingRepository;

    @Autowired
    private PaperOrderRepository paperOrderRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 계좌_비관적_잠금과_사용자별_원장_일괄삭제_쿼리가_동작한다() {
        User user = userRepository.save(User.builder()
                .email("paper-repository@ove.com")
                .password("hashed-password")
                .nickname("모의투자")
                .build());
        PaperAccount account = paperAccountRepository.save(PaperAccount.builder()
                .user(user)
                .balance(new BigDecimal("10000000"))
                .currency(PaperAccount.DEFAULT_CURRENCY)
                .build());
        paperHoldingRepository.save(PaperHolding.builder()
                .paperAccount(account)
                .symbol("KRW-BTC")
                .quantity(BigDecimal.ONE)
                .averagePurchasePrice(new BigDecimal("1000"))
                .build());
        paperOrderRepository.save(PaperOrder.builder()
                .paperAccount(account)
                .symbol("KRW-BTC")
                .side(OrderSide.BUY)
                .quantity(BigDecimal.ONE)
                .price(new BigDecimal("1000"))
                .amount(new BigDecimal("1000"))
                .build());
        paperAccountRepository.flush();

        UUID userId = user.getId();
        assertThat(paperAccountRepository.findWithLockByUserId(userId)).isPresent();

        paperOrderRepository.deleteAllByUserId(userId);
        paperHoldingRepository.deleteAllByUserId(userId);
        paperOrderRepository.flush();
        paperHoldingRepository.flush();

        assertThat(paperOrderRepository.count()).isZero();
        assertThat(paperHoldingRepository.count()).isZero();
    }

    @Test
    void 일괄삭제는_다른_사용자의_보유종목과_주문이력을_건드리지_않는다() {
        PaperAccount mine = accountWithLedger("mine@ove.com", "내계정");
        PaperAccount others = accountWithLedger("others@ove.com", "남의계정");
        paperAccountRepository.flush();

        paperOrderRepository.deleteAllByUserId(mine.getUser().getId());
        paperHoldingRepository.deleteAllByUserId(mine.getUser().getId());
        paperOrderRepository.flush();
        paperHoldingRepository.flush();

        // 내 원장만 사라지고 남의 원장은 그대로 남는다(서브쿼리가 사용자 기준으로만 걸러야 한다)
        UUID othersUserId = others.getUser().getId();
        assertThat(paperOrderRepository.findAllByPaperAccountUserId(othersUserId,
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(paperHoldingRepository.findAllByPaperAccountUserIdOrderBySymbolAsc(othersUserId)).hasSize(2);
        assertThat(paperOrderRepository.findAllByPaperAccountUserId(mine.getUser().getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(paperHoldingRepository.findAllByPaperAccountUserIdOrderBySymbolAsc(mine.getUser().getId()))
                .isEmpty();
    }

    // 계좌 한 개에 보유종목 2개와 주문 2건을 가진 사용자를 만든다
    private PaperAccount accountWithLedger(String email, String nickname) {
        User user = userRepository.save(User.builder().email(email).password("hashed-password")
                .nickname(nickname).build());
        PaperAccount account = paperAccountRepository.save(PaperAccount.builder().user(user)
                .balance(new BigDecimal("10000000")).currency(PaperAccount.DEFAULT_CURRENCY).build());
        for (String symbol : new String[] {"KRW-BTC", "KRW-ETH"}) {
            paperHoldingRepository.save(PaperHolding.builder().paperAccount(account).symbol(symbol)
                    .quantity(BigDecimal.ONE).averagePurchasePrice(new BigDecimal("1000")).build());
            paperOrderRepository.save(PaperOrder.builder().paperAccount(account).symbol(symbol).side(OrderSide.BUY)
                    .quantity(BigDecimal.ONE).price(new BigDecimal("1000")).amount(new BigDecimal("1000")).build());
        }
        return account;
    }
}
