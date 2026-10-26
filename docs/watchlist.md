# 관심 종목 API

Notion API 명세의 관심 종목 등록·조회·삭제 상세 페이지를 기준으로 구현했다.
공통 `/api` 경로와 `{statusCode,timestamp,path,message,data,error}` envelope를 사용한다.
모든 경로에 `Authorization: Bearer {accessToken}`이 필요하다.

| 메서드 | 경로 | 요청 | 성공 data |
| --- | --- | --- | --- |
| POST | /api/watchlist | `{"symbol":"KRW-BTC"}` | 201: `{symbol,addedAt}` |
| GET | /api/watchlist | 없음 | 200: `{items:[{symbol,koreanName,price,signal,confidence,addedAt}]}` |
| DELETE | /api/watchlist/{symbol} | 없음 | 200: `null` |

- 현재 종목 서비스와 동일하게 KRW-BTC, KRW-ETH, KRW-XRP를 지원한다.
- 입력 심볼은 공백 제거 후 대문자로 통일한다.
- 사용자별 최대 20개. 등록 시 사용자 행 잠금으로 동시 요청을 직렬화한다.
- `watchlists`의 `(user_id,symbol)` unique 제약으로 중복을 차단한다.
- 등록순으로 조회하며 같은 시각이면 ID순으로 정렬한다.
- POST 중복·최대 개수 초과: 409 `WATCHLIST_CONFLICT`.
- 미지원 종목: 404 `MARKET_NOT_FOUND`. 삭제할 관심 항목 없음: 404 `WATCHLIST_NOT_FOUND`.
- AI 모델·신호 저장소가 아직 없어 `signal`과 `confidence`는 현재 시장 API처럼 null이다. 예측값을 생성하지 않는다.
- 조회 시 외부 시세 요청이 실패해도 관심 목록은 반환하고, 조회하지 못한 `price`·`koreanName`은 null로 둔다.
- `/users/me`의 `stats.watchlistCount`는 저장된 실제 개수를 반환한다.
- 스키마는 기존 프로젝트의 Hibernate ddl-auto 설정을 따른다. 별도 마이그레이션 도구는 아직 없다.

검증: `./gradlew test --tests 'com.ove.watchlist.*' --tests 'com.ove.user.service.*' --tests 'com.ove.market.service.*' --tests 'com.ove.market.controller.*'`.
관심 API 테스트는 H2를 사용하고 Redis 토큰 저장소만 mock으로 대체한다. JWT 인증 필터, 요청 검증, 컨트롤러, 서비스, DB 저장·삭제는 실제 구성으로 실행한다.
