package com.ove.signal.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.signal.domain.Signal;
import com.ove.signal.domain.SignalType;
import com.ove.signal.dto.SignalHistoryResponse;
import com.ove.signal.dto.SignalResponse;
import com.ove.signal.repository.SignalRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignalService {

    private static final Duration DEFAULT_HISTORY_RANGE = Duration.ofDays(7);
    private static final Duration MAX_HISTORY_RANGE = Duration.ofDays(90);
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);
    private static final int MAX_PAGE_SIZE = 100;

    private final SignalRepository signalRepository;

    @Transactional(readOnly = true)
    public SignalResponse getLatest(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        Signal signal = signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc(normalizedSymbol)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNAL_NOT_FOUND));
        return SignalResponse.from(signal, signal.getGeneratedAt().isBefore(Instant.now().minus(STALE_AFTER)));
    }

    @Transactional(readOnly = true)
    public SignalHistoryResponse getHistory(String symbol, Instant from, Instant to, int page, int size) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1~100 사이여야 합니다.");
        }

        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(DEFAULT_HISTORY_RANGE) : from;
        if (effectiveFrom.isAfter(effectiveTo)
                || Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_HISTORY_RANGE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 기간은 최대 90일이며 from은 to보다 늦을 수 없습니다.");
        }

        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("generatedAt"), Sort.Order.desc("id")));
        Page<Signal> signals = signalRepository
                .findAllBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
                        normalizedSymbol, effectiveFrom, effectiveTo, pageable);
        return SignalHistoryResponse.from(signals);
    }

    /** 모델 추론 결과를 저장한다. 같은 신호가 이어질 때도 최신 결과를 보존하되, 전환 행만 이력에 노출한다. */
    @Transactional
    public Signal record(String symbol, SignalType nextSignal, BigDecimal buyProbability, BigDecimal holdProbability,
                         BigDecimal sellProbability, BigDecimal confidence, BigDecimal price) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (nextSignal == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "signal은 필수입니다.");
        }
        validateProbability(buyProbability);
        validateProbability(holdProbability);
        validateProbability(sellProbability);
        validateProbability(confidence);
        if (price == null || price.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "price는 0보다 커야 합니다.");
        }

        SignalType previousSignal = signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc(normalizedSymbol)
                .map(Signal::getSignal)
                .filter(previous -> previous != nextSignal)
                .orElse(null);
        Signal saved = signalRepository.save(Signal.builder()
                .symbol(normalizedSymbol)
                .previousSignal(previousSignal)
                .signal(nextSignal)
                .buyProbability(buyProbability)
                .holdProbability(holdProbability)
                .sellProbability(sellProbability)
                .confidence(confidence)
                .price(price)
                .build());
        return saved;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9]{2,10}-[A-Za-z0-9]{2,20}")) {
            throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
        }
        return symbol.toUpperCase(Locale.ROOT);
    }

    private void validateProbability(BigDecimal probability) {
        if (probability == null || probability.signum() < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "확률과 확신도는 0~1 사이여야 합니다.");
        }
    }
}
