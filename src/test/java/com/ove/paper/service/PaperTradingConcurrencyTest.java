package com.ove.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ove.account.domain.PaperAccount;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.MarketPriceProvider;
import com.ove.paper.domain.OrderSide;
import com.ove.paper.domain.PaperHolding;
import com.ove.paper.domain.PaperOrder;
import com.ove.paper.dto.request.PaperOrderRequest;
import com.ove.support.IntegrationTestSupport;
import com.ove.user.domain.User;

/**
 * 같은 계좌에 주문이 한꺼번에 몰려도(비관적 잠금) 잔고가 음수가 되지 않고, 체결 합계·보유수량·주문 수가
 * 서로 맞는지 확인한다. 서비스를 여러 스레드에서 직접 호출하므로 컨트롤러·보안 계층은 거치지 않는다.
 */
class PaperTradingConcurrencyTest extends IntegrationTestSupport {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000000");
    private static final BigDecimal PRICE = new BigDecimal("1000000"); // 1주(1개)당 100만원 → 초기 잔고로 최대 10개

    @Autowired
    private PaperTradingService paperTradingService;

    @MockitoBean
    private MarketPriceProvider marketPriceProvider;

    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder().email("concurrent@ove.com").password("hashed-password")
                .nickname("동시주문").build());
        paperAccountRepository.save(PaperAccount.builder().user(user).balance(INITIAL_BALANCE)
                .currency(PaperAccount.DEFAULT_CURRENCY).build());
        userId = user.getId();
        given(marketPriceProvider.getLatestPrice("KRW-BTC")).willReturn(PRICE);
    }

    @Test
    @DisplayName("동시 매수 20건: 잔고만큼인 10건만 체결되고 나머지는 잔고 부족, 잔고는 0이며 체결 합계가 차감액과 같다")
    void concurrentBuysNeverOverspend() throws Exception {
        List<ErrorCode> outcomes = placeConcurrently(OrderSide.BUY, 20);

        assertThat(outcomes).filteredOn(code -> code == null).hasSize(10);
        assertThat(outcomes).filteredOn(code -> code != null).hasSize(10).containsOnly(ErrorCode.INSUFFICIENT_BALANCE);

        BigDecimal balance = paperAccountRepository.findByUserId(userId).orElseThrow().getBalance();
        assertThat(balance).isGreaterThanOrEqualTo(BigDecimal.ZERO).isEqualByComparingTo(BigDecimal.ZERO);

        List<PaperOrder> orders = paperOrderRepository.findAll();
        assertThat(orders).hasSize(10);
        BigDecimal orderedTotal = orders.stream().map(PaperOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(orderedTotal).isEqualByComparingTo(INITIAL_BALANCE.subtract(balance));

        List<PaperHolding> holdings = paperHoldingRepository.findAll();
        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).getQuantity()).isEqualByComparingTo("10");
        assertThat(holdings.get(0).getAveragePurchasePrice()).isEqualByComparingTo(PRICE);
    }

    @Test
    @DisplayName("동시 매도 8건(보유 5개): 5건만 체결되고 3건은 보유 수량 부족, 보유종목은 사라지고 잔고는 원래대로 돌아온다")
    void concurrentSellsNeverOversell() throws Exception {
        placeSequentially(OrderSide.BUY, 5);

        List<ErrorCode> outcomes = placeConcurrently(OrderSide.SELL, 8);

        assertThat(outcomes).filteredOn(code -> code == null).hasSize(5);
        assertThat(outcomes).filteredOn(code -> code != null).hasSize(3).containsOnly(ErrorCode.INSUFFICIENT_HOLDING);
        assertThat(paperHoldingRepository.findAll()).isEmpty();
        // 같은 가격에 5개를 사서 5개를 팔았으니 잔고는 처음과 같다
        assertThat(paperAccountRepository.findByUserId(userId).orElseThrow().getBalance())
                .isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(paperOrderRepository.count()).isEqualTo(5 + 5);
    }

    @Test
    @DisplayName("매수·매도가 섞여 동시에 와도(5개 보유, 매수 5 + 매도 5) 모두 체결되고 보유 수량·잔고에 갱신 손실이 없다")
    void concurrentMixedOrdersKeepLedgerConsistent() throws Exception {
        placeSequentially(OrderSide.BUY, 5);

        List<ErrorCode> outcomes = placeConcurrentlyMixed(5, 5);

        // 어떤 순서로 처리돼도 잔고(5개 매수 후 500만원)와 보유(5개)가 모자라지 않아 전부 체결된다
        assertThat(outcomes).containsOnly((ErrorCode) null);
        List<PaperHolding> holdings = paperHoldingRepository.findAll();
        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).getQuantity()).isEqualByComparingTo("5");
        assertThat(paperAccountRepository.findByUserId(userId).orElseThrow().getBalance())
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(PRICE.multiply(new BigDecimal("5"))));
        assertThat(paperOrderRepository.count()).isEqualTo(5 + 10);
    }

    private void placeSequentially(OrderSide side, int count) {
        for (int i = 0; i < count; i++) {
            paperTradingService.placeOrder(userId, order(side));
        }
    }

    private List<ErrorCode> placeConcurrently(OrderSide side, int count) throws Exception {
        List<OrderSide> sides = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sides.add(side);
        }
        return run(sides);
    }

    private List<ErrorCode> placeConcurrentlyMixed(int buys, int sells) throws Exception {
        List<OrderSide> sides = new ArrayList<>();
        for (int i = 0; i < Math.max(buys, sells); i++) {
            if (i < buys) {
                sides.add(OrderSide.BUY);
            }
            if (i < sells) {
                sides.add(OrderSide.SELL);
            }
        }
        return run(sides);
    }

    // 모든 스레드가 준비된 뒤 한꺼번에 출발시킨다. 성공이면 null, 업무 예외면 그 오류 코드를 담는다.
    private List<ErrorCode> run(List<OrderSide> sides) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(sides.size());
        CountDownLatch ready = new CountDownLatch(sides.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (OrderSide side : sides) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        paperTradingService.placeOrder(userId, order(side));
                        return null;
                    } catch (BusinessException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private PaperOrderRequest order(OrderSide side) {
        return PaperOrderRequest.builder().symbol("KRW-BTC").side(side).quantity(BigDecimal.ONE).build();
    }
}
