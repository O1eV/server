package com.ove.model.client;

/** AI 서버 /api/v1/predict 응답. decision은 BUY / SELL / HOLD. */
public record ModelDecision(String decision, double buy, double sell, double hold, double confidence) {
}
