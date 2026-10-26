# 모의 거래 내역 조회

노션 API 명세에 맞춰 서버 모의 주문·포트폴리오·거래 내역을 연결한다.

- `GET /api/paper/orders?symbol=KRW-BTC&page=0&size=20` (Bearer 인증 필수)
- symbol 선택, page 0 이상, size 1~100.
- data: `{items:[{orderId,symbol,side,quantity,price,fee,signalAtOrder,executedAt}],page,size,totalElements}`.
- 로그인 사용자의 모의계좌에 속한 주문만 조회한다. orderedAt DESC, id DESC로 정렬한다.
- 기능 브랜치 `feature/paper-trading`의 PaperOrder 스키마와 저장소를 재사용했다. 명세 응답에 맞춰 조회 DTO는 flat items/page/size/totalElements로 제공한다.
- 과거 수수료·당시 신호가 저장되지 않은 이력은 null이다. 프론트에서는 미기록으로 표시한다.
- `POST /api/paper/orders`: `{symbol,side:"BUY"|"SELL",orderType:"MARKET",quantity}`. 클라이언트 가격은 사용하지 않는다.
- Redis 수집 현재가로 체결하며 오래된 시세·수집 중단 시 주문을 거절한다. 현재 지원 종목은 KRW-BTC, KRW-ETH, KRW-XRP다.
- 수수료는 체결 금액의 0.05%다. 매수 시 금액+수수료 차감, 매도 시 금액-수수료 입금한다. 계좌 잠금과 트랜잭션으로 주문·잔고·보유 수량을 함께 반영한다.
- 체결 당시 저장된 최신 AI 신호를 이력에 기록한다. 신호가 없으면 null이다.
- `GET /api/paper/portfolio`: cashBalance, totalPurchaseAmount, totalEvaluationAmount, totalProfitLoss, totalReturnRate, totalAssetAmount, holdings를 반환한다. 수익률 단위는 %이며 평가손익은 보유 자산의 미실현 손익이다.
- 프론트 기본 화면은 서버 모의투자다. 주문 후 포트폴리오와 거래 내역을 다시 조회하며 포트폴리오는 20초마다 갱신한다.
- 기존 브라우저 모의 주문과 서버 저장 이력을 혼동하지 않도록 포트폴리오 화면에 서버 모의투자/브라우저 연습 탭을 둔다. 브라우저 주문이 서버로 자동 업로드되지는 않는다.
- 프론트 조회 중 로딩·빈 결과·오류·재시도, 종목 필터와 페이지 이동을 제공한다.

검증: `./gradlew test --tests 'com.ove.paper.*' --tests 'com.ove.realtime.*'` 및 프론트 빌드·린트·포맷, `node scripts/test-paper-history.mjs`, `node scripts/test-realtime.mjs`.
