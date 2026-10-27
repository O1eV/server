package com.ove.signal.dto;

import java.math.BigDecimal;

/**
 * 종목 목록에 합칠 최신 AI 신호의 요약.
 * 신호 전체 필드가 필요한 단건 조회는 LatestSignalResponse를 쓴다.
 */
public record SignalSummary(String signal, BigDecimal confidence) {
}
