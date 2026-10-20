package com.ove.paper.dto;

import java.math.BigDecimal;

import com.ove.paper.domain.OrderSide;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 현재 시세 저장소가 만들어지기 전의 모의 주문 입력.
 * 추후 시세 도메인이 추가되면 price는 서버의 최신 체결가로 대체한다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaperOrderRequest {

    @NotBlank
    @Pattern(regexp = "^[A-Za-z]{2,10}-[A-Za-z0-9]{2,20}$",
            message = "symbol은 KRW-BTC 형식이어야 합니다.")
    private String symbol;

    @NotNull
    private OrderSide side;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @Digits(integer = 12, fraction = 12)
    private BigDecimal quantity;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @Digits(integer = 16, fraction = 8)
    private BigDecimal price;
}
