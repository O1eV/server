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

## UUID 매핑 배포 절차 (#53, PR #54)

`Watchlist.id`와 Repository ID 타입은 UUID다. Hibernate `ddl-auto=update`에
기존 bigint → UUID 변환을 맡기지 않는다. API 요청·응답 계약에는 변화가 없다.

1. 대상 DB에서 다음 조회로 실제 타입을 확인하고 결과를 배포 기록에 남긴다.
   `SELECT table_schema, data_type, is_identity, column_default FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'watchlists' AND column_name = 'id';`
2. `uuid`이면 데이터 전환 없이 새 버전을 배포한다. 테이블이 없으면 새 버전의 스키마 생성 절차를 따른다.
3. `bigint`이면 서버와 쓰기 작업을 중지하고 DB 백업 및 복원 가능 여부를 확인한다.
   변경 전 행 수와 `(user_id, symbol, added_at)`을 기록한다.
4. PostgreSQL 13 이상에서 [수동 전환 SQL](sql/watchlist-id-to-uuid.sql)을
   `psql -X --set ON_ERROR_STOP=1 --file docs/sql/watchlist-id-to-uuid.sql`로 실행한다.
   연결 정보는 환경의 기존 DB 설정을 사용하며 저장소나 로그에 자격 증명을 남기지 않는다.
   기본 스키마가 public이 아니면 검토 후 SQL의 스키마를 맞춘다.
5. 전환은 트랜잭션 안에서 행을 보존하고 ID만 새 UUID로 발급한다. PK 및
   `(user_id, symbol)` 유일 제약은 유지한다. 이미 UUID면 값을 바꾸지 않는다.
   참조 FK가 있거나 예상하지 못한 타입이면 중단한다. 실패한 세션은 종료해 롤백한다.
6. 타입, 행 수, 위 업무 컬럼 보존 여부를 확인하고 새 버전을 배포한다.
   로그인한 테스트 사용자로 `GET /api/watchlist`와 등록·삭제를 확인한다.
   같은 `added_at`의 항목은 새 UUID 기준으로 순서가 바뀔 수 있다.
7. 이전 Long 매핑 버전으로 되돌릴 때 UUID를 bigint로 캐스팅하지 않는다.
   쓰기를 중단하고 전환 전 백업을 복원한다. 전환 후 쓰기 데이터의 보존은 별도 계획이 필요하다.

이 SQL은 자동 실행하지 않는다. dev/운영 DB 타입 확인 및 실제 계정 API 확인은
배포 담당자가 대상 환경에서 수행해야 한다.
PostgreSQL 전환 회귀 검증(Docker 필요):
`./gradlew test --tests 'com.ove.migration.WatchlistUuidMigrationTest'`.

전환 구문 참고: [PostgreSQL ALTER TABLE 공식 문서](https://www.postgresql.org/docs/16/sql-altertable.html).
