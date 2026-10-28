package com.ove.paper.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.ove.paper.domain.OrderSide;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class PaperOrderRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 심볼_앞뒤_공백은_허용한다() {
        PaperOrderRequest request = PaperOrderRequest.builder()
                .symbol(" krw-btc ")
                .side(OrderSide.BUY)
                .quantity(BigDecimal.ONE)
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 형식이_잘못된_심볼은_거절한다() {
        PaperOrderRequest request = PaperOrderRequest.builder()
                .symbol("BTC")
                .side(OrderSide.BUY)
                .quantity(BigDecimal.ONE)
                .build();

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("symbol");
    }
}
