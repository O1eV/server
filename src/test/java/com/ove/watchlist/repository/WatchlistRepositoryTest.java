package com.ove.watchlist.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.ove.watchlist.domain.Watchlist;

import jakarta.persistence.EntityManager;

@DataJpaTest
class WatchlistRepositoryTest {

    @Autowired
    private WatchlistRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void readsExistingUuidAndGeneratesUuidForNewWatchlist() {
        UUID existingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        entityManager.createNativeQuery(
                "insert into watchlists (id, user_id, symbol, added_at) values (:id, :userId, :symbol, CURRENT_TIMESTAMP)")
                .setParameter("id", existingId)
                .setParameter("userId", userId)
                .setParameter("symbol", "KRW-BTC")
                .executeUpdate();

        assertThat(repository.findByUserIdOrderByAddedAtAscIdAsc(userId))
                .extracting(Watchlist::getId).containsExactly(existingId);

        Watchlist saved = repository.saveAndFlush(
                Watchlist.builder().userId(userId).symbol("KRW-ETH").build());
        UUID generatedId = saved.getId();
        assertThat(generatedId).isNotNull();
        entityManager.clear();
        assertThat(repository.findById(generatedId)).isPresent();
    }
}
