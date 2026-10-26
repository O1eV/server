package com.ove.signal.service;

import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.MarketService;
import com.ove.signal.repository.SignalRepository;
import com.ove.signal.dto.LatestSignalResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignalService {
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
}
