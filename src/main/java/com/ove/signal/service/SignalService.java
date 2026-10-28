package com.ove.signal.service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.MarketService;
import com.ove.signal.repository.SignalRepository;
import com.ove.signal.dto.LatestSignalResponse;
import com.ove.signal.dto.SignalSummary;
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

    /**
     * 종목 목록처럼 여러 종목의 최신 신호가 한 번에 필요할 때 사용한다.
     * 신호가 없는 종목은 결과에 넣지 않으므로 호출부에서 null로 처리한다.
     */
    @Transactional(readOnly = true)
    public Map<String, SignalSummary> latestSummaries(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        List<String> normalized = symbols.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return Map.of();
        }

        Map<String, SignalSummary> result = new LinkedHashMap<>();
        for (var signal : repository.findLatestBySymbolIn(normalized)) {
            result.put(signal.getSymbol(), new SignalSummary(signal.getSignal(), signal.getConfidence()));
        }
        return result;
    }
}
