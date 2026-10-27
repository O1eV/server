package com.ove.signal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.MarketService;
import com.ove.signal.repository.SignalRepository;
import com.ove.signal.dto.LatestSignalResponse;
import com.ove.signal.dto.SignalHistoryResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignalService {
    private static final Duration DEFAULT_HISTORY_RANGE = Duration.ofDays(7);
    private static final Duration MAX_HISTORY_RANGE = Duration.ofDays(90);
    private static final int MAX_PAGE_SIZE = 100;

    private final SignalRepository repository;

    @Transactional
    public void record(com.ove.signal.domain.Signal signal) {
        MarketService.requireSupportedSymbol(signal.getSymbol());
        var probabilities = java.util.Arrays.asList(signal.getBuyProbability(), signal.getHoldProbability(), signal.getSellProbability());
        if (signal.getSignal() == null || signal.getConfidence() == null || probabilities.stream().anyMatch(java.util.Objects::isNull)
                || !java.util.List.of("BUY", "HOLD", "SELL").contains(signal.getSignal())
                || probabilities.stream().anyMatch(value -> value.signum() < 0 || value.compareTo(java.math.BigDecimal.ONE) > 0)
                || probabilities.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                    .subtract(java.math.BigDecimal.ONE).abs().compareTo(new java.math.BigDecimal("0.001")) > 0
                || signal.getConfidence().signum() < 0 || signal.getConfidence().compareTo(java.math.BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        repository.save(signal);
    }

    @Transactional(readOnly = true)
    public LatestSignalResponse latest(String symbol) {
        String normalized = symbol.toUpperCase(Locale.ROOT);
        MarketService.requireSupportedSymbol(normalized);
        return repository.findFirstBySymbolOrderByGeneratedAtDescIdDesc(normalized)
                .map(value -> LatestSignalResponse.from(value, Instant.now()))
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNAL_NOT_FOUND));
    }

    // 신호 전환 이력(최신순). from 기본값은 to(기본 지금)에서 7일 전, 조회 기간은 최대 90일
    @Transactional(readOnly = true)
    public SignalHistoryResponse history(String symbol, Instant from, Instant to, int page, int size) {
        String normalized = symbol.toUpperCase(Locale.ROOT);
        MarketService.requireSupportedSymbol(normalized);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.");
        }
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(DEFAULT_HISTORY_RANGE);
        if (start.isAfter(end) || Duration.between(start, end).compareTo(MAX_HISTORY_RANGE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 기간은 최대 90일이며 from은 to보다 늦을 수 없습니다.");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("generatedAt"), Sort.Order.desc("id")));
        return SignalHistoryResponse.from(
                repository.findBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(normalized, start, end, pageable));
    }
}
