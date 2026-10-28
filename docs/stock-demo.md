# 주식 시연 API (발표용)

키움 수집기가 저장한 주식 시세를 읽기만 하는 조회 API다. 새 수집이나 키움·외부 호출은 하지 않는다.
키움 시세는 개인 키로 하는 시연 전용이라 prod에서는 열리지 않는다(`AGENTS.md`의 키움 규칙 참고).

## 열리는 조건

- `collector.kiwoom.enabled=true`(`KIWOOM_ENABLED=true`)이고 `prod` 프로필이 아닐 때만 빈이 만들어진다. 아니면 매핑이 없어 404다. 기본값은 꺼짐이다.
- 종목은 `KIWOOM_SYMBOLS`에 설정한 6자리 코드만 된다. 아니면 404 `MARKET_NOT_FOUND`.
- 인증은 기존 규칙대로 Bearer 토큰이 필요하다. 주식 STOMP/WebSocket 발행은 없다.

## GET /api/demo/stocks/{code}/price

Redis `demo:price:{code}` 해시(price, tradeTimestamp, updatedAt)를 읽는다. 응답은 코인 `GET /api/markets/{symbol}/price`와 같은 필드다.

```json
{ "symbol": "005930", "price": 71200, "tradeTimestamp": "2026-10-26T00:00:00Z",
  "updatedAt": "2026-10-26T00:00:00Z", "stale": false }
```

- `updatedAt`이 10초 넘게 오래되면 `stale=true`.
- 장 마감(키 만료)·미수신·Redis 장애·손상된 값: 503 `COLLECTOR_UNAVAILABLE`.

## GET /api/demo/stocks/{code}/candles

`candles` 테이블에 저장된 1분봉을 시간 오름차순으로 돌려준다. 응답은 코인 캔들과 같은 `{symbol, interval, candles[]}`다.

- 쿼리: `interval`(1m만, 기본 1m), `from`(포함), `to`(미포함, 생략 시 지금), `limit`(1~500, 기본 200).
- `to` 이전부터 과거로 `limit`개를 가져온 뒤 `from` 이후만 남긴다. 구간에 봉이 없으면 빈 목록.
- `interval`≠1m, limit 범위 밖, `from`>`to`: 400 `INVALID_REQUEST`. DB 장애: 503 `COLLECTOR_UNAVAILABLE`.
