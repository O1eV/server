package com.ove.signal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.ove.signal.domain.Signal;
import com.ove.signal.domain.SignalType;

@DataJpaTest
class SignalRepositoryTest {

    @Autowired
    private SignalRepository signalRepository;

    @Test
    @DisplayName("같은 신호가 이어져도 최신 스냅샷은 최신 id로 찾고, 이력에는 전환 행만 남는다")
    void latestAndHistory() {
        Signal first = signalRepository.save(signal(null, SignalType.HOLD));
        Signal changed = signalRepository.save(signal(SignalType.HOLD, SignalType.BUY));
        Signal same = signalRepository.save(signal(null, SignalType.BUY));
        signalRepository.flush();

        assertThat(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc("KRW-BTC"))
                .get()
                .extracting(Signal::getId)
                .isEqualTo(same.getId());

        Instant now = Instant.now();
        Page<Signal> history = signalRepository.findAllBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
                "KRW-BTC", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("generatedAt"), Sort.Order.desc("id"))));

        assertThat(history.getContent()).extracting(Signal::getId).containsExactly(changed.getId());
        assertThat(history.getTotalElements()).isEqualTo(1);
        assertThat(first.getId()).isNotNull();
    }

    private Signal signal(SignalType previousSignal, SignalType signal) {
        return Signal.builder()
                .symbol("KRW-BTC")
                .previousSignal(previousSignal)
                .signal(signal)
                .buyProbability(new BigDecimal("0.7"))
                .holdProbability(new BigDecimal("0.2"))
                .sellProbability(new BigDecimal("0.1"))
                .confidence(new BigDecimal("0.8"))
                .price(new BigDecimal("1000"))
                .build();
    }
}
