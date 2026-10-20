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
}
