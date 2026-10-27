package com.ove.signal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.signal.domain.Signal;

@SpringBootTest
@Transactional
class SignalRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");

    @Autowired
    SignalRepository repository;
    @MockitoBean
    TokenRedisRepository tokens;

    private Signal signal(String symbol, String previous, String value, Instant at) {
        return repository.saveAndFlush(new Signal(symbol, previous, value, new BigDecimal("0.7"), new BigDecimal("0.2"),
                new BigDecimal("0.1"), new BigDecimal("0.7"), new BigDecimal("100"), at, "model-v1"));
    }

    private Page<Signal> history(String symbol, Instant from, Instant to, int page, int size) {
        return repository.findBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(symbol, from, to,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("generatedAt"), Sort.Order.desc("id"))));
    }

    @Test
    @DisplayName("전환 행만, 해당 종목만, 최신순으로 조회한다")
    void returnsOnlyTransitionsNewestFirst() {
        signal("KRW-BTC", null, "HOLD", T0);                       // 첫 신호(전환 아님)
        Signal first = signal("KRW-BTC", "HOLD", "BUY", T0.plusSeconds(60));
        signal("KRW-BTC", null, "BUY", T0.plusSeconds(120));       // 같은 신호 지속
        Signal second = signal("KRW-BTC", "BUY", "SELL", T0.plusSeconds(180));
        signal("KRW-ETH", "HOLD", "BUY", T0.plusSeconds(90));      // 다른 종목

        Page<Signal> result = history("KRW-BTC", T0, T0.plusSeconds(600), 0, 10);

        assertThat(result.getContent()).extracting(Signal::getId).containsExactly(second.getId(), first.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("기간은 양 끝을 포함하고 바깥은 제외한다")
    void rangeIsInclusive() {
        signal("KRW-BTC", "HOLD", "BUY", T0.minusSeconds(1));
        Signal atFrom = signal("KRW-BTC", "BUY", "SELL", T0);
        Signal atTo = signal("KRW-BTC", "SELL", "HOLD", T0.plusSeconds(60));
        signal("KRW-BTC", "HOLD", "BUY", T0.plusSeconds(61));

        Page<Signal> result = history("KRW-BTC", T0, T0.plusSeconds(60), 0, 10);

        assertThat(result.getContent()).extracting(Signal::getId).containsExactly(atTo.getId(), atFrom.getId());
    }

    @Test
    @DisplayName("페이지로 나눠 조회한다")
    void paginates() {
        for (int i = 0; i < 5; i++) {
            signal("KRW-BTC", "HOLD", "BUY", T0.plusSeconds(i));
        }

        Page<Signal> second = history("KRW-BTC", T0, T0.plusSeconds(10), 1, 2);

        assertThat(second.getContent()).hasSize(2);
        assertThat(second.getContent().get(0).getGeneratedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(second.getTotalElements()).isEqualTo(5);
        assertThat(second.getTotalPages()).isEqualTo(3);
    }
}
