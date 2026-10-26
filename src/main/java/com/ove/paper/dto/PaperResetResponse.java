package com.ove.paper.dto;

import java.math.BigDecimal;

/** 모의 계좌 초기화 명세의 응답. */
public record PaperResetResponse(BigDecimal cashBalance) {}
