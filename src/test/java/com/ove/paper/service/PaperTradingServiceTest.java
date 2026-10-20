package com.ove.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.paper.domain.OrderSide;
import com.ove.paper.domain.PaperHolding;
import com.ove.paper.domain.PaperOrder;
import com.ove.paper.dto.PaperOrderHistoryResponse;
import com.ove.paper.dto.PaperOrderRequest;
import com.ove.paper.dto.PaperOrderResponse;
import com.ove.paper.dto.PaperPortfolioResponse;
import com.ove.paper.repository.PaperHoldingRepository;
import com.ove.paper.repository.PaperOrderRepository;

@ExtendWith(MockitoExtension.class)
class PaperTradingServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private PaperAccountRepository paperAccountRepository;

    @Mock
    private PaperHoldingRepository paperHoldingRepository;

    @Mock
    private PaperOrderRepository paperOrderRepository;

    private PaperTradingService paperTradingService;

    private PaperAccount account;

    @BeforeEach
    void setUp() {
        account = account("10000000");
        paperTradingService = new PaperTradingService(paperAccountRepository, paperHoldingRepository,
                paperOrderRepository, new BigDecimal("10000000"));
    }

    @Test
    void 매수하면_잔고를_차감하고_신규_보유종목과_체결이력을_만든다() {
        given(paperAccountRepository.findWithLockByUserId(USER_ID)).willReturn(Optional.of(account));
        given(paperHoldingRepository.findByPaperAccountIdAndSymbol(account.getId(), "KRW-BTC"))
                .willReturn(Optional.empty());
        given(paperOrderRepository.save(any(PaperOrder.class))).willAnswer(invocation -> savedOrder(invocation.getArgument(0)));

        PaperOrderResponse response = paperTradingService.placeOrder(USER_ID, order("krw-btc", OrderSide.BUY, "2", "1000"));

        assertThat(response.getSymbol()).isEqualTo("KRW-BTC");
        assertThat(response.getAmount()).isEqualByComparingTo("2000");
        assertThat(account.getBalance()).isEqualByComparingTo("9998000");

        ArgumentCaptor<PaperHolding> holdingCaptor = ArgumentCaptor.forClass(PaperHolding.class);
        then(paperHoldingRepository).should().save(holdingCaptor.capture());
        assertThat(holdingCaptor.getValue().getQuantity()).isEqualByComparingTo("2");
        assertThat(holdingCaptor.getValue().getAveragePurchasePrice()).isEqualByComparingTo("1000");
    }

    @Test
    void 추가_매수는_이동평균_매수가를_갱신한다() {
        PaperHolding holding = holding("KRW-BTC", "2", "1000");
        given(paperAccountRepository.findWithLockByUserId(USER_ID)).willReturn(Optional.of(account));
        given(paperHoldingRepository.findByPaperAccountIdAndSymbol(account.getId(), "KRW-BTC"))
                .willReturn(Optional.of(holding));
        given(paperOrderRepository.save(any(PaperOrder.class))).willAnswer(invocation -> savedOrder(invocation.getArgument(0)));

        paperTradingService.placeOrder(USER_ID, order("KRW-BTC", OrderSide.BUY, "2", "2000"));

        assertThat(holding.getQuantity()).isEqualByComparingTo("4");
        assertThat(holding.getAveragePurchasePrice()).isEqualByComparingTo("1500");
        assertThat(account.getBalance()).isEqualByComparingTo("9996000");
    }

    @Test
    void 전량_매도하면_보유종목을_삭제하고_현금을_입금한다() {
        PaperHolding holding = holding("KRW-BTC", "2", "1000");
        given(paperAccountRepository.findWithLockByUserId(USER_ID)).willReturn(Optional.of(account));
        given(paperHoldingRepository.findByPaperAccountIdAndSymbol(account.getId(), "KRW-BTC"))
                .willReturn(Optional.of(holding));
        given(paperOrderRepository.save(any(PaperOrder.class))).willAnswer(invocation -> savedOrder(invocation.getArgument(0)));

        paperTradingService.placeOrder(USER_ID, order("KRW-BTC", OrderSide.SELL, "2", "1200"));

        assertThat(account.getBalance()).isEqualByComparingTo("10002400");
        then(paperHoldingRepository).should().delete(holding);
    }

    @Test
    void 잔고와_보유수량_부족은_각각_도메인_오류를_돌려준다() {
        given(paperAccountRepository.findWithLockByUserId(USER_ID)).willReturn(Optional.of(account));

        assertThatThrownBy(() -> paperTradingService.placeOrder(USER_ID,
                order("KRW-BTC", OrderSide.BUY, "1", "10000001")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        given(paperHoldingRepository.findByPaperAccountIdAndSymbol(account.getId(), "KRW-BTC"))
                .willReturn(Optional.empty());
        assertThatThrownBy(() -> paperTradingService.placeOrder(USER_ID,
                order("KRW-BTC", OrderSide.SELL, "1", "1000")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_HOLDING);
    }

    @Test
    void 포트폴리오_조회와_초기화는_계좌_상태를_정확히_반영한다() {
        PaperHolding holding = holding("KRW-ETH", "3", "1000");
        given(paperAccountRepository.findByUserId(USER_ID)).willReturn(Optional.of(account));
        given(paperHoldingRepository.findAllByPaperAccountUserIdOrderBySymbolAsc(USER_ID)).willReturn(List.of(holding));

        PaperPortfolioResponse portfolio = paperTradingService.getPortfolio(USER_ID);

        assertThat(portfolio.getCashBalance()).isEqualByComparingTo("10000000");
        assertThat(portfolio.getTotalPurchaseAmount()).isEqualByComparingTo("3000");
        assertThat(portfolio.getTotalAssetAmount()).isEqualByComparingTo("10003000");

        given(paperAccountRepository.findWithLockByUserId(USER_ID)).willReturn(Optional.of(account));
        PaperPortfolioResponse reset = paperTradingService.reset(USER_ID);

        assertThat(reset.getCashBalance()).isEqualByComparingTo("10000000");
        assertThat(reset.getHoldings()).isEmpty();
        then(paperOrderRepository).should().deleteAllByUserId(USER_ID);
        then(paperHoldingRepository).should().deleteAllByUserId(USER_ID);
    }

    @Test
    void 주문이력은_심볼_필터와_최신순_페이지를_서비스에_전달한다() {
        PaperOrder savedOrder = savedOrder(PaperOrder.builder()
                .paperAccount(account)
                .symbol("KRW-BTC")
                .side(OrderSide.BUY)
                .quantity(new BigDecimal("1"))
                .price(new BigDecimal("1000"))
                .amount(new BigDecimal("1000"))
                .build());
        given(paperOrderRepository.findAllByPaperAccountUserIdAndSymbol(eq(USER_ID), eq("KRW-BTC"), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of(savedOrder), PageRequest.of(1, 10), 11));

        PaperOrderHistoryResponse response = paperTradingService.getOrders(USER_ID, "krw-btc", 1, 10);

        assertThat(response.getOrders()).hasSize(1);
        assertThat(response.getPageInfo().getPage()).isEqualTo(1);
        assertThat(response.getPageInfo().getTotalElements()).isEqualTo(11);
    }

    private PaperAccount account(String balance) {
        PaperAccount result = PaperAccount.builder()
                .balance(new BigDecimal(balance))
                .currency(PaperAccount.DEFAULT_CURRENCY)
                .build();
        ReflectionTestUtils.setField(result, "id", 1L);
        return result;
    }

    private PaperHolding holding(String symbol, String quantity, String price) {
        PaperHolding result = PaperHolding.builder()
                .paperAccount(account)
                .symbol(symbol)
                .quantity(new BigDecimal(quantity))
                .averagePurchasePrice(new BigDecimal(price))
                .build();
        ReflectionTestUtils.setField(result, "id", 1L);
        return result;
    }

    private PaperOrderRequest order(String symbol, OrderSide side, String quantity, String price) {
        return PaperOrderRequest.builder()
                .symbol(symbol)
                .side(side)
                .quantity(new BigDecimal(quantity))
                .price(new BigDecimal(price))
                .build();
    }

    private PaperOrder savedOrder(PaperOrder order) {
        ReflectionTestUtils.setField(order, "id", 1L);
        ReflectionTestUtils.setField(order, "orderedAt", Instant.parse("2026-10-20T00:00:00Z"));
        return order;
    }
}
