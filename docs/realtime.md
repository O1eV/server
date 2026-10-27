# 실시간 시세·신호 구독

노션 `/ws (STOMP)` API 명세(0998d692a65783149833014a31378b8d)를 기준으로 연결했다.

- WebSocket `/ws`, STOMP CONNECT의 선택 헤더 `Authorization: Bearer {accessToken}`.
- 공개 시세 `/topic/prices/{symbol}`. 신호 `/topic/signals/{symbol}` 및 개인 알림 `/user/queue/alerts`는 인증 필수.
- 양방향 heartbeat 10초. 프론트는 끊김 시 1초부터 최대 30초까지 지수 백오프로 재접속한다.
- 시세 Redis `prices:*` → PriceRelay → STOMP 구독자. 수집기를 켜려면 `COLLECTOR_ENABLED=true`와 Redis·TimescaleDB가 필요하다.
- 백엔드는 로그아웃·탈퇴·만료 토큰을 구독 시점과 인증 메시지 발송 시점에 확인한다.
- 신호 발행은 `RealtimePublisher.publishSignalChange`를 사용한다. AI 모델 호출은 아직 연결되지 않아 실제 모델 이벤트가 없으면 신호가 수신되지 않는다.
- 시세 메시지: `type,symbol,price,changeRate,volume,time`. 현재 Upbit 수집 원본에서 changeRate는 제공하지 않으므로 null이며 프론트는 REST 기준 전일 종가로 등락률을 계산한다. volume은 해당 체결량이다.
- 신호 메시지: `type,symbol,previousSignal,signal,probabilities,confidence,generatedAt,stale`. probabilities를 전달하는 오버로드를 제공한다. 기존 발행 함수처럼 확률이 주어지지 않으면 null로 유지한다. 기존 time 필드도 호환성을 위해 유지한다.
- 개인 알림: `type=SIGNAL_CHANGED,symbol,from,to,confidence,time`.
- 프론트는 대시보드·코인 상세에서 연결하고 이탈·종목 변경·인증 변경 시 이전 연결을 해제한다. 최신 신호와 최근 관심 알림을 화면에 표시한다.
- Vite `/ws` 프록시를 추가했다. 배포 환경은 `VITE_WS_URL`을 지정한다. 서버의 cors.allowed-origins에도 프론트 origin을 등록해야 한다.
- 코인 현재가는 STOMP로 받고 차트 캔들은 기존 REST에서 20초마다 갱신한다. 체결 tick으로 거래량·OHLCV를 임의 생성하지 않는다.

검증: 실제 STOMP 클라이언트 통합 테스트(공개 시세·보호 신호·관심 알림·잘못된 구독·SEND 금지·로그아웃·만료)와 프론트 메시지/설정 검증 스크립트를 실행한다.

## 최신 신호 조회

인증 후 `GET /api/signals/{symbol}`로 최신 신호 한 건을 조회한다. 생성 후 2분이 지나면 `stale=true`, 저장된 신호가 없으면 `404 SIGNAL_NOT_FOUND`를 반환한다. 신호 이력 조회는 #46에서 담당한다.

모의투자·계좌·주문 가격 조회는 dev에 머지된 #19 구현을 그대로 사용한다. Redis 수집 시세 기반 주문 공급자는 이 PR에 포함하지 않는다.
