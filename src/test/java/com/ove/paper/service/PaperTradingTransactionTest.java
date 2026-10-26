package com.ove.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.auth.repository.TokenRedisRepository;
import com.ove.market.service.MarketPriceProvider;
import com.ove.paper.domain.OrderSide;
import com.ove.paper.domain.PaperHolding;
import com.ove.paper.dto.PaperOrderRequest;
import com.ove.paper.repository.PaperHoldingRepository;
import com.ove.paper.repository.PaperOrderRepository;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;

/**
 * 업비트 시세를 부르는 동안 DB 커넥션(트랜잭션)을 잡고 있지 않은지 확인한다.
 * Redis는 쓰지 않고, JWT 필터가 참조하는 토큰 저장소만 목으로 바꿔 Docker 없이 돌린다.
 */
@SpringBootTest
class PaperTradingTransactionTest {

    @Autowired
    private PaperTradingService paperTradingService;

    @Autowired
    private PaperAccountRepository paperAccountRepository;

    @Autowired
    private PaperHoldingRepository paperHoldingRepository;

    @Autowired
    private PaperOrderRepository paperOrderRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private MarketPriceProvider marketPriceProvider;

    @MockitoBean
    private TokenRedisRepository tokenRedisRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("paper-transaction@ove.com")
                .password("hashed-password")
                .nickname("트랜잭션")
                .build());
        paperAccountRepository.save(PaperAccount.builder()
                .user(user)
                .balance(new BigDecimal("10000000"))
                .currency(PaperAccount.DEFAULT_CURRENCY)
                .build());
        userId = user.getId();
    }

    @AfterEach
    void tearDown() {
        paperOrderRepository.deleteAllInBatch();
        paperHoldingRepository.deleteAllInBatch();
        paperAccountRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("주문: 종목 확인·시세 조회는 트랜잭션 밖, 체결만 트랜잭션 안에서 한다")
    void priceLookupRunsOutsideTransaction() {
        willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).given(marketPriceProvider).validateMarket(anyString());
        given(marketPriceProvider.getLatestPrice(anyString())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new BigDecimal("1000");
        });

        paperTradingService.placeOrder(userId, PaperOrderRequest.builder()
                .symbol("KRW-BTC")
                .side(OrderSide.BUY)
                .quantity(BigDecimal.ONE)
                .build());

        assertThat(paperOrderRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("포트폴리오: 보유 종목을 읽은 뒤 시세는 트랜잭션 밖에서 조회한다")
    void portfolioPriceLookupRunsOutsideTransaction() {
        PaperAccount account = paperAccountRepository.findByUserId(userId).orElseThrow();
        paperHoldingRepository.save(PaperHolding.builder()
                .paperAccount(account)
                .symbol("KRW-BTC")
                .quantity(BigDecimal.ONE)
                .averagePurchasePrice(new BigDecimal("1000"))
                .build());
        given(marketPriceProvider.getLatestPrices(anyList())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return java.util.Map.of("KRW-BTC", new BigDecimal("1200"));
        });

        assertThat(paperTradingService.getPortfolio(userId).getTotalEvaluationAmount())
                .isEqualByComparingTo("1200");
    }
}
