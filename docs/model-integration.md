# AI 모델 서버 연동

코인 1분봉이 마감될 때마다 AI 서버(`POST {MODEL_BASE_URL}/api/v1/predict`)를 호출해 BUY/HOLD/SELL 신호를 만든다. 코인(업비트)만 대상이고 키움 주식은 해당 없다.

## 켜는 조건

기본은 **꺼짐**이다. 아래를 모두 줄 때만 동작하고, 꺼져 있으면 모델 연동 빈이 만들어지지 않아 키·서버가 없어도 기존 동작 그대로다.

| 환경변수 | 설명 |
|---|---|
| `MODEL_ENABLED=true` | 연동 켜기 |
| `COLLECTOR_ENABLED=true` | 캔들 저장·마감 알림이 있어야 한다(함께 켜야 함) |
| `MODEL_BASE_URL` | AI 서버 주소(필수) |
| `MODEL_API_KEY` | `X-API-KEY` 헤더 값. 코드·로그에 남기지 않는다(비우면 헤더 없이 호출) |

설정(`model.*`): `timeout`(기본 500ms), `candle-count`(기본 30, 최소 15).

## 흐름

1. 수집기가 1분봉을 마감하면 Redis `candles.closed:{symbol}`로 알린다.
2. `CandleClosedListener`가 알림을 받아 **단일 스레드 executor**에 넘긴다(대기열 30, 넘치면 오래된 것부터 버림). 수집기·Redis 수신은 막히지 않고, 어떤 예외도 밖으로 퍼지지 않는다.
3. `SignalGenerationService`가 `candles`에서 최근 N개를 읽는다. **15개 미만이면 호출하지 않는다.**
4. `ModelClient`가 캔들 배열(`open, high, low, close, volume, bar_time`, 오래된 것부터)을 보낸다. 500ms 안에 못 받거나 5xx면 **1회 재시도**하고, 4xx는 재시도하지 않는다.
5. 성공하면 `RealtimePublisher.publishSignalChange`가 `signals`에 저장하고 `/topic/signals/{symbol}`로 발행한다. 확률은 합 1로 맞추고 confidence는 0~1로 제한한다.

## 신호 변경 알림

- 직전 신호와 **다른 신호로 바뀐 때만** `previousSignal`을 채우고 관심 종목 알림(`/user/queue/alerts`)을 보낸다.
- 첫 신호나 같은 신호가 이어질 때는 `previousSignal=null`로 저장하고 `/topic/signals/{symbol}`만 갱신한다(알림 없음).

## 실패 처리

- 호출 실패·잘못된 응답이면 새 신호를 저장하지 않고 마지막 신호를 유지한다. 마지막 신호가 있으면 `/topic/signals/{symbol}`에 `stale=true`로 다시 알린다.
- 에러 코드 `MODEL_UNAVAILABLE`(503)이 `ModelClient`에서 던져지고 서비스가 잡아 처리한다. 최신 신호 REST는 기존대로 생성 2분 후 `stale=true`다.
- 로그에는 키와 응답 본문을 남기지 않는다.
