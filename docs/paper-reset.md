# 모의 계좌 초기화

명세: https://app.notion.com/p/paper-reset-3fa8d692a6578228a4fe01a3bd5995c6

- `POST /api/paper/reset`, `Authorization: Bearer {accessToken}`. 요청 본문·쿼리 없음.
- 로그인한 사용자의 계좌를 잠그고 한 트랜잭션에서 주문·보유 내역 삭제 및 초기 잔고 복구를 처리한다. 다른 사용자의 계좌에는 영향이 없다.
- 기본 초기 잔고는 10,000,000 KRW (`paper-account.initial-balance`). 시세 수집기가 꺼져 있어도 초기화할 수 있다.
- 200 응답의 data는 `{ "cashBalance": 10000000 }`, message는 `모의 계좌가 초기화되었습니다.`이다.
- 서버 모의투자 화면에서 초기화 내용 확인 후 호출한다. 처리 중 중복 클릭과 주문을 막고, 기존 포트폴리오 조회를 중단한다. 성공 시 보유·손익을 비우고 반환된 잔고를 표시하며 포트폴리오·거래 내역을 다시 조회한다.
- 네트워크 오류 시 완료 여부를 단정하지 않고 계좌·거래 내역 재확인을 안내한다. 브라우저 연습 계좌는 별도다.

검증: `./gradlew test --tests 'com.ove.paper.*'` (본인 계좌 복구, 주문·보유 삭제, 타 계좌 유지, 반복 요청, 비로그인 거절), 프론트 `node scripts/test-paper-history.mjs`, 빌드·린트·포맷 검사.
