# 종목 목록·카테고리 필터 API

Notion 기능 명세서의 **종목 목록·카테고리 필터**와 API 명세서의 `/markets`를 기준으로 구현했다.
공통 `/api` 경로와 `{statusCode,timestamp,path,message,data,error}` envelope를 사용한다.

| 메서드 | 경로 | 인증 | 설명 |
| --- | --- | --- | --- |
| GET | /api/markets | 선택 | 자산군·시장 상태·신호로 필터링한 종목 목록 |

## 쿼리 파라미터

| 이름 | 값 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `assetClass` | `COIN`, `KR_STOCK`, `US_STOCK` | 없음(전체) | 자산군 필터. 현재 공개 API는 코인만 제공하므로 `COIN` 외에는 빈 목록 |
| `marketState` | `UP`, `DOWN`, `SIDEWAYS`, `VOLATILE` | 없음(전체) | 전일 대비 변동률 기준 시장 상태 |
| `signal` | `BUY`, `HOLD`, `SELL` | 없음(전체) | 최신 AI 신호 필터. **로그인 필요** |
| `page` | 0 이상 정수 | `0` | 페이지 번호(0부터 시작) |
| `size` | 1~100 정수 | `20` | 페이지 크기 |

정해진 값이 아닌 필터, 범위를 벗어난 `page`·`size`, 숫자가 아닌 값은 `400 INVALID_REQUEST`다.
필터 값은 앞뒤 공백을 무시하고 대소문자를 구분하지 않는다(예: ` coin ` = `COIN`, ` buy ` = `BUY`).
`signal` 필터를 비로그인으로 보내면 필터 값 검증보다 먼저 `401 UNAUTHORIZED`를 돌려준다.

## 동작

- 현재 공개 API는 코드에 고정된 코인 3종목(`KRW-BTC`, `KRW-ETH`, `KRW-XRP`)만 제공한다.
- 종목 이름은 업비트 `/v1/market/all`(설정값 `upbit.markets-cache-ttl`, 기본 5분 캐시),
  현재가와 전일 대비 변동률은 업비트 `/v1/ticker`에서 조회한다.
- `assetClass`는 현재 항상 `COIN`이다.
- `marketState`는 전일 대비 변동률(`signed_change_rate`)로 정한다.
  - 절댓값 5% 이상: `VOLATILE`
  - +1% 이상: `UP`
  - -1% 이하: `DOWN`
  - 그 외: `SIDEWAYS`
  - 업비트 응답에 현재가나 변동률이 없으면 시장 상태를 만들 수 없으므로 `502 UPSTREAM_UNAVAILABLE`로 응답한다.
- **로그인한 사용자**에게는 종목별 최신 신호의 `signal`·`confidence`를 합친다.
  - 최신 신호는 `generatedAt` 내림차순, 같은 시각이면 `id` 내림차순으로 고른다.
  - 신호가 없는 종목은 `signal`·`confidence`가 `null`이다.
  - 오래된 신호도 최신 신호이면 그대로 포함한다(별도 `stale` 필드는 목록에 없다).
- **비로그인 사용자**의 `signal`·`confidence`는 항상 `null`이며, 신호 저장소를 조회하지 않는다.
- `signal` 필터는 로그인 사용자의 최신 신호에만 적용한다. 신호가 없거나 다른 종목은 제외된다.
- 필터를 모두 적용한 뒤 `page`·`size`로 자른다. `totalElements`는 필터 적용 후 전체 건수다.
- 범위를 넘는 `page`는 빈 `items`를 돌려준다.

## 응답 예시

```json
{
  "statusCode": 200,
  "timestamp": "2026-10-27T04:00:00Z",
  "path": "/api/markets",
  "message": "요청에 성공했습니다.",
  "data": {
    "items": [
      {
        "symbol": "KRW-BTC",
        "koreanName": "비트코인",
        "englishName": "Bitcoin",
        "assetClass": "COIN",
        "marketState": "UP",
        "price": 98500000,
        "changeRate": 0.0123,
        "signal": "BUY",
        "confidence": 0.71
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1
  },
  "error": null
}
```

비로그인 응답은 같은 형태에서 `signal`·`confidence`만 `null`이다.

## 오류 코드

| 상태 | 코드 | 의미 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 필터 값·페이지 범위·타입 오류 |
| 401 | `UNAUTHORIZED` | 비로그인 상태에서 `signal` 필터 사용 |
| 502 | `UPSTREAM_UNAVAILABLE` | 업비트 호출 실패(429·5xx·연결 실패·타임아웃·빈/깨진 응답) |

## 아직 하지 않은 것

Notion API 명세의 장기 계획 중 다음 항목은 이번 구현에 포함하지 않았다.

- 종목 마스터 `markets` 테이블 도입: 현재는 코드에 고정된 3종목을 쓴다.
- 현재가를 시세 수집기 Redis 값으로 전환: Redis 현재가 해시에는 전일 대비 변동률이 없어
  `marketState` 계산 방식도 함께 바꿔야 하므로 별도 작업이 필요하다.
- 국내·해외 주식 공개: 키움 시세는 개인 키 로컬 시연용이라 공개 API로 노출하지 않는다.

## 검증

```bash
./gradlew test \
  --tests 'com.ove.market.service.MarketServiceTest' \
  --tests 'com.ove.market.controller.MarketControllerTest' \
  --tests 'com.ove.signal.LatestSignalIntegrationTest'
```

Testcontainers(Docker)가 필요한 통합 테스트는 GitHub Actions의 `Build and Test`에서 확인한다.
