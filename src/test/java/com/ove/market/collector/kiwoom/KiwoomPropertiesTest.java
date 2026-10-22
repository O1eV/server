package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KiwoomPropertiesTest {

    private static final String KEY = "TEST-APP-KEY-not-real";
    private static final String SECRET = "TEST-APP-SECRET-not-real";

    @Test
    @DisplayName("올바른 설정은 통과하고, 종목 코드는 대문자로 정규화된다")
    void validConfiguration() {
        KiwoomProperties properties = props(List.of(" 005930", "00593a"), KEY, SECRET, "https://api.kiwoom.com",
                "wss://api.kiwoom.com:10000/api/dostk/websocket", false, null);

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.normalizedSymbols()).containsExactly("005930", "00593A");
    }

    @Test
    @DisplayName("App Key·Secret이 비어 있으면 환경변수 이름만 알려 주고 값은 말하지 않는다")
    void requiresKeys() {
        assertThatThrownBy(() -> props(List.of("005930"), "", SECRET, "https://a", "wss://a", false, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KIWOOM_APP_KEY").hasMessageContaining("KIWOOM_APP_SECRET")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET));
        assertThatThrownBy(() -> props(List.of("005930"), KEY, null, "https://a", "wss://a", false, null).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("종목 코드는 6자리만 허용하고 거래소 접미사(_NX, _AL)와 형식 오류는 거부한다")
    void validatesSymbols() {
        for (String bad : new String[] {"05930", "0059300", "005930_NX", "005930_AL", "KRW-BTC", "00593!", ""}) {
            assertThatThrownBy(() -> props(List.of("005930", bad), KEY, SECRET, "https://a", "wss://a", false, null)
                    .validate())
                    .as("symbol=%s", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("종목 코드");
        }
        assertThatThrownBy(() -> props(List.of(), KEY, SECRET, "https://a", "wss://a", false, null).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("종목은 최대 200개까지만 허용한다 (실시간 등록 합계 한도)")
    void limitsSymbolCount() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add(String.format("%06d", i));
        }
        assertThatThrownBy(() -> props(many, KEY, SECRET, "https://a", "wss://a", false, null).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("200");
        assertThatCode(() -> props(many.subList(0, 200), KEY, SECRET, "https://a", "wss://a", false, null).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("암호화되지 않은 주소는 거부하고, 로컬 가짜 서버(loopback)만 예외로 허용한다")
    void requiresSecureUrls() {
        assertThatThrownBy(() -> props(List.of("005930"), KEY, SECRET, "http://api.kiwoom.com", "wss://a", false, null)
                .validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> props(List.of("005930"), KEY, SECRET, "https://a", "ws://api.kiwoom.com:10000", false,
                null).validate()).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> props(List.of("005930"), KEY, SECRET, "http://127.0.0.1:8080", "ws://127.0.0.1:9090",
                false, null).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("분봉 보충을 켜려면 분봉 시각의 의미(START/END)를 먼저 정해야 한다")
    void backfillRequiresLabel() {
        assertThatThrownBy(() -> props(List.of("005930"), KEY, SECRET, "https://a", "wss://a", true, null).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("minute-bar-label");
        assertThatThrownBy(() -> props(List.of("005930"), KEY, SECRET, "https://a", "wss://a", true, "MIDDLE")
                .validate()).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> props(List.of("005930"), KEY, SECRET, "https://a", "wss://a", true, "start").validate())
                .doesNotThrowAnyException();
        assertThat(props(List.of("005930"), KEY, SECRET, "https://a", "wss://a", true, "end").minuteBarLabelIsEnd())
                .isTrue();
    }

    @Test
    @DisplayName("toString은 App Key·Secret 값을 보여 주지 않는다")
    void toStringMasksSecrets() {
        String text = props(List.of("005930"), KEY, SECRET, "https://a", "wss://a", false, null).toString();

        assertThat(text).doesNotContain(KEY).doesNotContain(SECRET).contains("<설정됨>");
        assertThat(props(List.of("005930"), "", "", "https://a", "wss://a", false, null).toString())
                .contains("<비어 있음>");
    }

    private KiwoomProperties props(List<String> symbols, String key, String secret, String restUrl, String wsUrl,
                                   boolean backfill, String label) {
        return new KiwoomProperties(true, symbols, key, secret, restUrl, wsUrl, Duration.ofMinutes(10),
                Duration.ofSeconds(120), Duration.ofSeconds(15), Duration.ofMillis(250), Duration.ofMillis(500),
                Duration.ofSeconds(3), backfill, label, "1");
    }
}
