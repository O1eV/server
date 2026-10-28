package com.ove.paper.dto.request;

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
 * 모의 주문 입력. 체결가는 조작할 수 없도록 서버가 최신 시세로 결정한다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaperOrderRequest {

    // 앞뒤 공백은 서비스에서 제거하므로 검증 단계에서도 허용한다.
    @NotBlank
    @Pattern(regexp = "^\\s*[A-Za-z]{2,10}-[A-Za-z0-9]{2,20}\\s*$",
            message = "symbol은 KRW-BTC 형식이어야 합니다.")
    private String symbol;

    @NotNull
    private OrderSide side;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @Digits(integer = 12, fraction = 12)
    private BigDecimal quantity;

}
