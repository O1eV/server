package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.ove.market.collector.kiwoom.KiwoomProperties;
import com.ove.market.collector.kiwoom.KiwoomRestClient;
import com.ove.market.collector.kiwoom.KiwoomTokenProvider;
import com.ove.market.collector.kiwoom.KiwoomTradeSource;
import com.ove.support.IntegrationTestSupport;

/**
 * 기본 설정(collector.enabled=false, collector.kiwoom.enabled=false)에서는 수집기 관련 빈이 하나도 만들어지지 않아서
 * 업비트·키움에 연결하지도, candles 테이블을 만들지도 않는다.
 */
class CollectorDisabledTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationContext context;

    @Test
    void 기본값에서는_수집기_빈이_없다() {
        assertThat(context.getBeanNamesForType(MarketCollector.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TradeSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CandleRepository.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MarketRedisPublisher.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CollectorProperties.class)).isEmpty();
        // 키움 수집기도 기본값(collector.kiwoom.enabled=false)에서는 아무것도 만들지 않는다 (토큰 발급·연결 없음)
        assertThat(context.getBeanNamesForType(KiwoomProperties.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KiwoomTokenProvider.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KiwoomTradeSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KiwoomRestClient.class)).isEmpty();
    }
}
