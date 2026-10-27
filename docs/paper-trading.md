# 모의투자 API

실제 거래소에 주문하지 않고, 서버가 조회한 업비트 현재가로 즉시 체결하는 모의투자 원장이다.
공통 `/api` 경로와 `{statusCode,timestamp,path,message,data,error}` envelope를 사용한다.
모든 경로에 `Authorization: Bearer {accessToken}`이 필요하다.

| 메서드 | 경로 | 요청 | 성공 data |
| --- | --- | --- | --- |
| POST | /api/paper/orders | `{symbol, side, quantity}` | 201: `{orderId,symbol,side,quantity,price,amount,orderedAt}` |
| GET | /api/paper/orders | `symbol`(선택), `page`, `size` | 200: `{orders:[...],pageInfo}` |
| GET | /api/paper/portfolio | 없음 | 200: `{currency,cashBalance,totalPurchaseAmount,totalEvaluationAmount,totalProfitLoss,totalReturnRate,totalAssetAmount,pricesComplete,holdings:[...]}` |
| POST | /api/paper/reset | 없음 | 200: 초기화된 포트폴리오 |

## 주문

- `side`는 `BUY` 또는 `SELL`이다. 현재는 주문과 동시에 체결되는 시장가 모의 주문만 지원한다.
- 요청에 `price`를 넣어도 무시한다. 체결가는 서버가 업비트에서 조회한 최신가만 사용한다.
- `quantity`는 0보다 커야 하며, 반올림 후 주문 금액이 0이면 `ORDER_AMOUNT_TOO_SMALL`(400)이다.
- 잔고 부족은 `INSUFFICIENT_BALANCE`(409), 보유 수량 부족은 `INSUFFICIENT_HOLDING`(409)이다.
- 입력 심볼은 공백 제거 후 대문자로 통일한다.

## 상장폐지·거래 중단 종목

- 거래소 종목 목록에 없는 종목은 보유 중이어도 주문할 수 없다. **매도도 `MARKET_NOT_FOUND`(404)로 거절된다.**
- 해당 보유 종목은 `POST /api/paper/reset`으로만 정리할 수 있다.
- 시세가 없는 종목을 평균 매수가로 청산하는 기능은 MVP 범위에서 제공하지 않는다.

## 포트폴리오

- 계좌와 보유 종목은 한 읽기 트랜잭션으로 같은 시점에 읽고, 시세는 트랜잭션 밖에서 조회한다.
- 일부 종목의 시세를 못 구하면 그 종목만 평균 매수가로 평가한다.
  이때 `pricesComplete=false`, 해당 종목의 `priceAvailable=false`이며, 총 평가 금액과 종목별 평가 금액은 서로 맞는다.
- `totalReturnRate`는 퍼센트 단위(예: `50.0000`)이고 소수점 4자리다.
- `totalAssetAmount = cashBalance + totalEvaluationAmount`이다.
- 보유 종목이 없으면 `pricesComplete=true`, `holdings=[]`이다.

## 오류 코드

| 상태 | 코드 | 의미 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 요청 형식·페이지·수량 검증 실패 |
| 400 | `ORDER_AMOUNT_TOO_SMALL` | 반올림 후 주문 금액이 0 |
| 401 | `TOKEN_EXPIRED`, `UNAUTHORIZED` | 인증 실패 |
| 404 | `MARKET_NOT_FOUND` | 거래소 종목 목록에 없는 종목(매도 포함) |
| 404 | `PAPER_ACCOUNT_NOT_FOUND` | 모의투자 계좌 없음 |
| 409 | `INSUFFICIENT_BALANCE` | 잔고 부족 |
| 409 | `INSUFFICIENT_HOLDING` | 보유 수량 부족 |
| 503 | `MARKET_PRICE_UNAVAILABLE` | 업비트 장애로 종목 확인·현재가 조회 불가 |

## 검증

`./gradlew test --tests 'com.ove.paper.*' --tests 'com.ove.market.service.UpbitMarketPriceProviderTest' --tests 'com.ove.global.config.OpenApiDocsTest'`
