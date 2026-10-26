# 수집 시세 API 연결

`GET /api/markets/{symbol}/price`는 기존 Upbit WebSocket 수집기가 저장하는 Redis `price:{symbol}` 해시를 읽는다. 인증 없이 조회할 수 있으며 응답 캐시는 금지한다.

공통 응답 envelope의 data:

```json
{
  "symbol": "KRW-BTC",
  "price": 98500000,
  "tradeTimestamp": "2026-10-26T00:00:00Z",
  "updatedAt": "2026-10-26T00:00:00Z",
  "stale": false
}
```

- 지원 종목: KRW-BTC, KRW-ETH, KRW-XRP 중 실제 collector.symbols에 설정한 종목.
- updatedAt이 10초 이상 오래되면 stale=true.
- 수집 비활성, 미수신, Redis 장애, 불완전한 해시: 503 COLLECTOR_UNAVAILABLE.
- 미지원 심볼: 404 MARKET_NOT_FOUND.
- 프론트 코인 시세 화면은 요청 완료 후 1초 간격으로 조회한다. 종목 전환·페이지 이탈 시 요청과 타이머를 취소한다.
- 정상 수집 현재가는 가격과 전일 대비 등락률에 반영한다. 전일 종가는 기존 ticker API에서 계산한다.
- 수집 지연·실패 시 기존 REST 시세로 돌아가고 화면에 연결 상태를 표시한다.
- 캔들은 기존 API에서 20초 간격으로 조회한다. 가격 tick으로 OHLCV 캔들을 임의 생성하지 않는다.
- 이 연결은 REST 폴링이며 브라우저용 STOMP WebSocket 구현은 포함하지 않는다.

## 실행 조건

기존 TimescaleDB와 Redis가 필요하다(`docker compose up -d`). 백엔드 실행 환경에 `COLLECTOR_ENABLED=true`를 지정한다. JWT_SECRET·DB 등 기존 서버 환경 설정도 유지해야 한다. 수집기는 활성화 시 candles 스키마를 생성하므로 TimescaleDB 확장이 필요하다.

서버를 재시작한 뒤 /api/markets/KRW-BTC/price에 수집된 시세가 내려오는지 확인한다. 미수신 중에는 503이며 첫 체결을 수신하면 200으로 바뀐다.

테스트: `./gradlew test --tests 'com.ove.market.service.*' --tests 'com.ove.market.controller.*' --tests 'com.ove.market.collector.CollectorDisabledTest' --tests 'com.ove.market.collector.MarketCollectorEndToEndTest'`
통합 테스트는 가짜 업비트 WebSocket 서버에서 전송한 체결을 실제 Redis·TimescaleDB에 반영하고 REST 응답까지 검증한다(Docker 필요).
