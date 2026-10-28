# AWS 배포 가이드 (EC2 + Docker Compose + CloudFront)

## 구성

```
브라우저 ─HTTPS→ CloudFront(*.cloudfront.net) ─HTTP:80→ EC2
                                                       └ nginx(프론트 이미지)  /      정적 파일(SPA)
                                                                              /api   → app:8080
                                                                              /ws    → app:8080 (STOMP WebSocket)
                                                         app(Spring) ── db(TimescaleDB), redis(AOF)
                                                         app ──(SSH 역터널)──→ 사용자 맥의 AI 서버(MPS)
```

- EC2 Docker는 nginx(프론트), Spring, TimescaleDB, Redis만 띄운다. AI 모델 서버는 사용자 맥에서 돌린다.
- HTTPS는 CloudFront가 맡는다. EC2에는 80 포트만 연다.
- 파일: `Dockerfile`, `docker-compose.prod.yml`, `.env.example`, `.github/workflows/deploy.yml`

## 다른 저장소와의 인터페이스

| 대상 | 규격 |
| --- | --- |
| 프론트 이미지(font 저장소) | `FRONT_IMAGE` 환경변수로 이미지 전체 참조(태그 포함)를 받는다. 컨테이너 포트 80. 같은 출처로 `/api`, `/ws`를 백엔드 `app:8080`에 프록시한다(백엔드 서비스명 `app`, 포트 `8080`). |
| AI 서버(ai-sever 저장소) | Spring이 `POST {MODEL_BASE_URL}/api/v1/predict` 호출, 헤더 `X-API-KEY: {MODEL_API_KEY}`. 환경변수 `MODEL_ENABLED`, `MODEL_BASE_URL`, `MODEL_API_KEY`. |

## 환경변수 (`.env`, 체크리스트 #34 기준)

`.env.example`을 복사해 EC2의 배포 폴더에 `.env`로 둔다. 값은 코드·문서·채팅에 적지 않는다.

| 변수 | 설명 |
| --- | --- |
| `APP_IMAGE`, `FRONT_IMAGE` | 이미지 전체 참조. `APP_IMAGE`는 배포 워크플로가 갱신한다. |
| `DB_USERNAME`, `DB_PASSWORD` | TimescaleDB 계정. DB·Redis는 외부에 포트를 열지 않는다. |
| `JWT_SECRET` | 32바이트 이상 무작위 문자열. |
| `CORS_ALLOWED_ORIGINS` | CloudFront 주소(`https://xxxx.cloudfront.net`, 끝 슬래시 없음). 틀리면 로그인·재발급이 403이다. |
| `COLLECTOR_ENABLED`, `COLLECTOR_SYMBOLS` | 코인 시세 수집기. 운영에서는 켠다. |
| `MODEL_ENABLED`, `MODEL_BASE_URL`, `MODEL_API_KEY` | AI 연동(#56, dev 머지 전에는 값이 있어도 무시된다). |
| `DDL_AUTO` | 현재 마이그레이션 도구(Flyway)가 없어 `update`로 스키마를 맞춘다. Flyway 도입 시 `validate`로 바꾸고 이 문서를 갱신한다. |

compose에 **고정**되어 `.env`로 바꿀 수 없는 값:

- `AUTH_COOKIE_SECURE=true`
- `SWAGGER_UI_ENABLED=false`, `API_DOCS_ENABLED=false`
- `SERVER_FORWARD_HEADERS_STRATEGY=framework` (`server.forward-headers-strategy`, CloudFront·nginx 뒤에서 https 스킴 인식)
- `SPRING_PROFILES_ACTIVE=prod` (로컬 시연용 `/api/demo/*`가 열리지 않는다)
- **`KIWOOM_ENABLED=false` : 키움 주식 수집기는 로컬 시연 전용이라 운영에서 절대 켜지 않는다.** 키움 App Key/Secret도 EC2에 두지 않는다.

## AI 서버 터널 (맥 → EC2)

컨테이너가 호스트에 열린 터널에 닿도록 SSH 역터널을 docker0 브릿지 주소에 바인딩한다. compose의 `app`에는 `extra_hosts: host.docker.internal:host-gateway`가 있다.

1. EC2: `/etc/ssh/sshd_config`에 `GatewayPorts clientspecified`를 추가하고 `sudo systemctl restart sshd`.
2. EC2: docker0 주소 확인(`ip -4 addr show docker0`, 보통 `172.17.0.1`). 이 주소가 다르면 아래 명령과 보안그룹 설명의 주소를 맞춘다.
3. 맥: AI 서버를 `127.0.0.1:8000`에 실행(`MODEL_API_KEY` 설정). 절차는 ai-sever 저장소 문서.
4. 맥: 터널 열기
   ```bash
   ssh -N -R 172.17.0.1:8000:127.0.0.1:8000 \
       -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes \
       -i <키 파일> ec2-user@<EC2 주소>
   ```
5. EC2 `.env`: `MODEL_ENABLED=true`, `MODEL_BASE_URL=http://host.docker.internal:8000`, `MODEL_API_KEY=`(맥과 같은 값) 후 `docker compose -f docker-compose.prod.yml up -d`.
6. 확인: `docker compose -f docker-compose.prod.yml exec app curl -s -o /dev/null -w '%{http_code}\n' http://host.docker.internal:8000/` 로 응답이 오면 도달 가능하다.

터널이 끊기면: Spring은 500ms 타임아웃 + 1회 재시도 뒤 `MODEL_UNAVAILABLE`로 처리한다. 새 신호는 만들지 않고 **마지막 신호를 `stale=true`로 표시**해 화면에 계속 보여 준다. 터널을 다시 열면 다음 1분봉 마감부터 정상으로 돌아온다(재시작 불필요).

## 사용자가 AWS 콘솔에서 할 일

- [ ] **EC2**: Amazon Linux 2023, t3.small 이상(메모리 2GB는 빠듯하다. DB·Redis·JVM을 함께 올리므로 t3.medium 권장), 디스크 20GB 이상. Docker와 compose 플러그인 설치, SSM Agent 동작 확인.
- [ ] **보안그룹**: 인바운드 80은 CloudFront 관리형 프리픽스 목록(`com.amazonaws.global.cloudfront.origin-facing`)만 허용. 22는 본인 IP만(터널용). 5432·6379·8080은 열지 않는다.
- [ ] **EC2 인스턴스 프로파일(IAM 역할)**: `AmazonSSMManagedInstanceCore` + 해당 ECR 저장소 pull 권한(`ecr:GetAuthorizationToken`, `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer`).
- [ ] **ECR**: 백엔드 저장소 1개(프론트 저장소는 프론트 쪽에서 준비). 기존 `here-server` 저장소는 건드리지 않는다.
- [ ] **GitHub OIDC 공급자 + 배포 역할**: 신뢰 정책을 이 저장소(`main` 또는 수동 실행 ref)로 제한. 권한은 ECR push(`ecr:GetAuthorizationToken`, `BatchCheckLayerAvailability`, `InitiateLayerUpload`, `UploadLayerPart`, `CompleteLayerUpload`, `PutImage`)와 SSM(`ssm:SendCommand` 대상 인스턴스, `ssm:GetCommandInvocation`, `ssm:ListCommandInvocations`).
- [ ] **EC2 초기 세팅**: 배포 폴더 생성(예: `/opt/ove`), `.env` 작성(값 직접 입력), 최초 1회 프론트 이미지 태그 지정.
- [ ] **CloudFront**: 오리진 = EC2 퍼블릭 DNS, 프로토콜 HTTP 80. 동작(Behavior):
  - `/api/*` : 캐시 비활성화(CachingDisabled), 오리진 요청 정책 AllViewer(헤더·쿠키·쿼리 전달), 메서드 전체 허용
  - `/ws*` : 캐시 비활성화, AllViewer, 메서드 전체 허용(WebSocket은 별도 설정 없이 업그레이드 헤더를 전달한다), 오리진 응답 시간 초과를 60초로 늘린다
  - 기본(`*`) : 정적 파일. 기본 캐시 사용 가능(nginx가 `index.html`은 no-cache, `/assets/`는 1년 캐시를 내려 준다)
  - 뷰어 프로토콜: HTTP를 HTTPS로 리디렉션
- [ ] **GitHub 저장소 설정**: 아래 Secrets/Variables 등록.

## GitHub Actions 배포 (`.github/workflows/deploy.yml`)

- 트리거: `main` push(PR 머지) 또는 수동 실행(workflow_dispatch). 팀 규칙상 `main`이 배포 브랜치이고 직접 push는 금지다. 시연 전 임시 배포는 수동 실행으로 한다.
- 동작: `./gradlew test` 통과 후(실패하면 배포하지 않는다) OIDC로 역할 가정 → ECR 로그인 → 이미지 빌드·push(태그 = 커밋 SHA) → SSM으로 EC2에서 compose 파일 갱신, `.env`의 `APP_IMAGE` 갱신, `pull` + `up -d`. SSM 결과는 최대 10분까지 폴링하고, 성공이 아니면 EC2의 표준출력·오류를 Actions 로그에 찍고 실패 처리한다.
- 프론트 이미지(`FRONT_IMAGE`)는 이 워크플로가 바꾸지 않는다. 서버 `.env`의 값을 그대로 쓴다.

필요한 이름(값은 저장소 설정에서 입력):

| 종류 | 이름 | 설명 |
| --- | --- | --- |
| Secret | `AWS_DEPLOY_ROLE_ARN` | OIDC로 맡을 배포 역할 ARN |
| Variable | `AWS_REGION` | 예: 리전 코드 |
| Variable | `ECR_APP_REPOSITORY` | 백엔드 ECR 저장소 이름 |
| Variable | `EC2_INSTANCE_ID` | 배포 대상 인스턴스 ID |
| Variable | `EC2_DEPLOY_DIR` | EC2의 배포 폴더(`.env`와 compose 위치) |

## 배포 후 확인

1. `docker compose -f docker-compose.prod.yml ps` 에서 4개 서비스가 healthy/running.
2. CloudFront 주소로 접속해 화면 로딩, 로그인(쿠키 Secure), 실시간 시세(WebSocket) 확인.
3. `https://<CloudFront>/swagger-ui.html`, `/v3/api-docs` 에서 Swagger 화면·OpenAPI JSON이 나오지 않는지 확인한다(프론트 화면이 대신 보이거나 404여야 정상). `/api/v3/api-docs`가 아니라 위 두 경로 기준이다.
4. 터널·AI 연동은 위 "AI 서버 터널" 6번과 신호 갱신, 끊었을 때 `stale` 표시 확인.
