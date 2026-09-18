# 기여 안내

앞쪽은 **기여 절차**, 뒤쪽은 [**개발 환경 구성**](#개발-환경-구성)이다. 앱을 쓰기만 할 거라면
[INSTALL.md](INSTALL.md)로 도커 스택을 띄우는 편이 빠르다.

문서·이슈·커밋은 한국어를 기본으로 쓴다. 영어로 보내주셔도 된다.

---

# 기여 절차

## 시작하기 전에

이 저장소는 [행동 규범](CODE_OF_CONDUCT.md)을 따른다.

무엇이 필요한지는 [ROADMAP.md](ROADMAP.md)에 있다 — 지금 버전의 한계와 다음에 만들 것을 적어두었다.

## 이슈 먼저

버그든 기능이든 이슈를 먼저 연다. 브랜치 이름에 그 번호를 쓴다.

**의존성 추가·아키텍처 변경·큰 리팩터링은 코드를 쓰기 전에 이슈에서 합의한다** — 방향이 갈리면
다 만들어온 작업이 통째로 버려진다.

**보안 취약점은 예외다** — 공개 이슈로 열지 않는다. [SECURITY.md](SECURITY.md)의 비공개 통로를 쓴다.

이슈를 열면 종류에 따라 양식이 나온다. 미리 준비해두면 오가는 횟수가 준다.

- **버그** — 재현 절차, 기대한 결과와 실제 결과, 버전(`git rev-parse --short HEAD`), 로그
- **설치·실행이 막혔을 때** — 운영체제, 설치 방식, 막힌 단계, 로그
- **기능 제안** — 지금 무엇이 불편한지, 어떻게 되면 좋겠는지
- **질문** — 무엇이 궁금한지, 무엇을 하려다 막혔는지

로그를 붙이기 전에 `.env`의 비밀번호·시크릿·API 키가 섞이지 않았는지 확인한다 — 이슈는 누구나 볼 수 있다.

## 포크

저장소에 쓰기 권한이 없다면 포크에서 작업한다. GitHub에서 **Fork**를 누르고 자기 계정의
사본을 클론한 뒤, 원본을 `upstream`으로 등록해 둔다.

```bash
git clone https://github.com/<내-계정>/onggijonggi-chat.git
cd onggijonggi-chat
git remote add upstream https://github.com/SUNJOOSOFT/onggijonggi-chat.git
```

## 브랜치

`dev`에서 따고 `<이슈번호>-<짧은-설명>`으로 이름 짓는다.

```
42-session-rename
57-token-audience-npe
```

포크에서 작업한다면 원본의 최신 `dev`에서 딴다.

```bash
git fetch upstream
git checkout -b 42-session-rename upstream/dev
```

## 코드 스타일

**백엔드** — 자바 클래스에는 파일 상단에 이 헤더를 둔다. 저장소의 모든 자바 파일이 지키고 있다.

```java
/**
 * Class Name : ChatController.java
 * Description : 01·CLIENT ↔ 03·CORE 채팅 스트리밍·이력 조회 계약 구현체.
 *               02·EDGE(SecurityConfig)가 JWT 인증·hasRole("USER")를 필터체인에서 이미 강제한다.
 */
```

`Description`에 나오는 `01·CLIENT`·`03·CORE` 같은 계층 번호는 [README의 문서 절](README.md#문서)에
설명이 있다. 그 밖의 포맷은 자동 검사가 없으니 고치는 파일의 기존 스타일을 따른다.

**프론트엔드** — biome가 정한다(`frontend/biome.jsonc`). `bun format`은 **자기가 고친 파일에만**
돌린다 — 저장소 전체에 돌리면 지금은 무관한 파일까지 바뀐다.

## 테스트

동작이 바뀌는 변경에는 테스트를 붙인다. 붙이기 어렵다면 PR에 왜 어려운지 적는다 — 거기서부터
이야기하면 된다.

백엔드 테스트는 H2로 돌아 DB나 Keycloak을 띄우지 않아도 된다. `gradlew build`에 테스트가 포함되므로
따로 `test`를 돌릴 필요는 없다(개별 실행은 [5. BFF](#5-bff) 참고).

프론트엔드는 vitest로 돈다. `bun run test`(1회 실행) 또는 `bun run test:watch`(감시 모드)로 돌리고,
`bun lint:check`도 함께 통과해야 한다.

## 커밋

```
타입(scope): 요약
```

타입은 `feat` `fix` `docs` `test` `chore` `refactor`, scope는 `frontend` `backend` `worker` `infra`
`docs` `scripts` 중 하나를 쓰고 여러 곳에 걸치면 생략한다.

```
feat(backend): 세션 이름변경 엔드포인트 추가
fix(frontend): aud 클레임 없는 토큰 NPE 수정
docs: 빠른 시작 누락 단계 보완
```

## PR

`dev`로 보낸다. 저장소 기본 브랜치가 `dev`라 PR을 열면 대상이 자동으로 잡힌다 — `main`으로
바뀌어 있으면 되돌린다. **PR 제목도 커밋과 같은 `타입(scope): 요약` 규칙으로 쓴다** — squash
머지라 제목이 그대로 `dev`의 커밋 메시지가 된다. 그 제목이 릴리스 버전을 정하므로(아래
"릴리스") 타입을 정확히 쓴다.

본문의 `Closes #`에 이슈 번호를 적으면 머지될 때 이슈가 함께 닫힌다. 이슈를 닫는 PR이 아니면
`Refs #`로 바꾼다.

- [ ] `gradlew build` 통과 (백엔드를 고쳤다면 — 테스트가 함께 돈다)
- [ ] `bun lint:check` 통과 (프론트를 고쳤다면)
- [ ] 동작이 바뀌었다면 테스트를 붙였다 (어렵다면 그 이유를 PR에 적는다)
- [ ] 관련 문서를 함께 고쳤다

PR을 열면 `static-checks`가 마이그레이션 SQL의 용어집·설계 불변식·Flyway 버전과 Java 클래스 헤더를 자동 검사한다. 나머지 항목은 직접 확인한다.
`flyway-postgres`는 빈 PostgreSQL 16에 전체 migration을 실제 적용한다. Hibernate 매핑과 SQL 제약의 의미는 별도 절차로 확인한다.

승인 1명이면 메인테이너가 **squash**로 머지한다 — PR 하나가 `dev`에 커밋 하나로 남는다.
리뷰가 오래 조용하면 PR에 댓글로 깨워주면 된다.

## 릴리스

`main`은 릴리스 브랜치다. 기여자가 직접 PR을 보내지 않고, 메인테이너가 `dev`를 `main`으로
머지해 릴리스한다.

태깅은 자동이다. `main`에 푸시되면 `release-tag.yml`이 `scripts/compute-next-version.mjs`로
다음 버전을 계산해 태그와 GitHub Release를 만든다 — 계산 근거가 커밋 제목의 conventional
commit 타입이라 규칙을 지키지 않으면 버전이 틀어진다. `dev`에 푸시되면
`dev-prerelease-tag.yml`이 `vX.Y.Z-dev.N` 형태의 prerelease 태그를 붙인다.

### 긴급 수정(hotfix)

이미 릴리스된 `main`을 당장 고쳐야 할 때만 쓴다. `dev`에 쌓인 다른 변경을 함께 내보내지 않으려는
것이 목적이므로, 급하지 않으면 평소대로 `dev`로 보낸다.

```bash
git fetch upstream
git checkout -b 57-token-audience-npe upstream/main   # dev가 아니라 main에서 딴다
```

PR을 열 때 **대상 브랜치를 `main`으로 직접 바꾼다.** 기본 브랜치가 `dev`라 그대로 두면 `dev`로
올라가고, 그러면 긴급 수정이 아니라 평범한 PR이 된다.

머지되면 `release-tag.yml`이 patch 버전을 자동 태깅한다(`fix:`는 patch bump).

**머지 뒤 메인테이너가 `main`을 `dev`로 역머지한다.** 이걸 빼먹으면 `dev`에서는 그 태그가
도달 불가라, `compute-next-version.mjs`가 이미 나간 버전을 다음 버전으로 다시 계산한다 —
`v0.2.1`이 릴리스된 뒤에도 `dev`가 `v0.2.1-dev.N`을 계속 붙이게 되고, 다음 릴리스의 버전이
어긋난다. 수정 자체도 `dev`에 없으니 후속 작업이 그 위에서 이뤄지지 않는다.

## 라이선스

이 저장소에 보낸 기여는, 명시적으로 달리 밝히지 않는 한 [Apache License 2.0](LICENSE)으로 제공하는
것으로 본다(Apache-2.0 §5). CLA나 DCO 서명은 요구하지 않는다.

---

# 개발 환경 구성

코드를 고치면서 개발할 때의 구성이다.

프론트와 BFF는 호스트에서 직접 실행해 핫리로드를 쓰고, 나머지는 컨테이너로 띄워 붙인다.

```
호스트:     frontend(3000) · bff(8090)
컨테이너:   keycloak(8081) · postgres(5442) · LLM 엔드포인트
```

도커 구성([INSTALL.md](INSTALL.md))과 두 가지가 다르다 — 프론트를 **호스트에서 직접 띄워** 주소가
`http://localhost:3010`이 아닌 `localhost:3000`이고, **LiteLLM을 거치지 않아** BFF가 LLM 엔드포인트에
직접 붙는다. 그래서 쓸 모델도 `litellm_config.yaml`이 아니라 BFF 설정에서 정한다(4단계).

## 준비물

- **JDK 17** — `build.gradle` 툴체인이 17로 고정돼 있다. **`JAVA_HOME`이 17을 가리키는지 확인한다** (`java -version`) — 옛 JDK가 잡혀 있으면 gradle wrapper가 배포본을 내려받는 단계에서 인증서 오류로 먼저 막힌다
- **Bun** — https://bun.sh · 프론트엔드는 **npm·yarn 대신 반드시 bun을 쓴다** — 다른 도구로 설치하면 `package-lock.json` 같은 lock file이 생겨 `bun.lock`과 어긋난다
- **Docker**
- **OpenAI 호환 LLM 엔드포인트** — [Ollama](https://ollama.com) 또는 상용 API 키

## 클론 후 한 번만

```bash
git config core.hooksPath .githooks
```

커밋될 내용을 네 갈래로 검사한다 — 마이그레이션 SQL의 DB 식별자 이름·설계 불변식·Flyway 버전,
그리고 자바 파일의 클래스 헤더. 걸렸다면 푸는 방법이 다르다.

- **용어집** — 아직 사전에 없는 단어를 썼다. [`scripts/glossary/words.md`](scripts/glossary/words.md)에
  단어를 등재하고 `node scripts/build-glossary.mjs`로 사전을 다시 만든 뒤 커밋한다.
- **설계 불변식** — 설계 계약을 깨는 변경이다. 등재로는 풀리지 않으니 설계를 바꿔야 한다. 무엇을
  왜 막는지는 `scripts/validate-invariants.mjs` 상단 주석에 적혀 있다.
- **Flyway 버전** — 전체 트리의 버전이 중복됐거나, 새·이름 변경 migration이 UTC 타임스탬프 형식이 아니다.
  새 파일은 `node scripts/flyway-migration.mjs create <lowercase_snake_case_설명>`으로 만든다. 최신 `dev`를
  반영한 뒤 충돌한 아직 영구 적용 전 파일만 `node scripts/flyway-migration.mjs renumber <파일> --confirm-not-permanently-applied`로 재번호화한다.
  `outOfOrder`는 서로 다른 낮은 버전의 나중 적용만 허용할 뿐 중복 버전을 해결하지 못하므로 켜지 않는다.
  이관 적용 순서가 환경마다 달라질 수 있어, 충돌은 재번호화로 해소한다.
- **자바 클래스 헤더** — 타입 선언 앞에 위 "코드 스타일" 절의 `Class Name :`/`Description :`
  헤더가 없다. 그 예시 형태로 채운다.

훅을 설치하지 않았거나 우회했더라도 PR에서 CI가 같은 검사를 돌린다.

---

## 1. Keycloak

`infra/config/realm-app.json`을 임포트하면 realm·client·역할·계정이 함께 만들어진다.

**macOS · Linux**

```bash
docker run -d --name dev-keycloak -p 8081:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -e KEYCLOAK_REALM=app-realm \
  -e KEYCLOAK_CLIENT_ID=ogjg-client \
  -e KEYCLOAK_CLIENT_SECRET=devsecret \
  -e PUBLIC_FRONTEND_URL=http://localhost:3000 \
  -e APP_USER=devuser -e APP_USER_PASSWORD=devpass123 \
  -v "$(pwd)/infra/config/realm-app.json:/opt/keycloak/data/import/realm-app.json:ro" \
  quay.io/keycloak/keycloak:26.0 start-dev --import-realm
```

**Windows (PowerShell)**

```powershell
docker run -d --name dev-keycloak -p 8081:8080 `
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin `
  -e KEYCLOAK_REALM=app-realm `
  -e KEYCLOAK_CLIENT_ID=ogjg-client `
  -e KEYCLOAK_CLIENT_SECRET=devsecret `
  -e PUBLIC_FRONTEND_URL=http://localhost:3000 `
  -e APP_USER=devuser -e APP_USER_PASSWORD=devpass123 `
  -v "${PWD}/infra/config/realm-app.json:/opt/keycloak/data/import/realm-app.json:ro" `
  quay.io/keycloak/keycloak:26.0 start-dev --import-realm
```

`http://localhost:8081/realms/app-realm/.well-known/openid-configuration`이 응답하면 준비된 것이다.

`PUBLIC_FRONTEND_URL`이 리다이렉트 주소가 되므로 프론트를 다른 포트로 띄운다면 여기서 맞춘다.
realm은 최초 기동 때만 만들어진다 — 값을 바꾸려면 `docker rm -f dev-keycloak` 후 다시 띄운다.

## 2. PostgreSQL

**macOS · Linux**

```bash
docker run -d --name dev-postgres -p 5442:5432 \
  -e POSTGRES_USER=appuser -e POSTGRES_PASSWORD=devpassword -e POSTGRES_DB=appdb \
  postgres:16-alpine
```

**Windows (PowerShell)**

```powershell
docker run -d --name dev-postgres -p 5442:5432 `
  -e POSTGRES_USER=appuser -e POSTGRES_PASSWORD=devpassword -e POSTGRES_DB=appdb `
  postgres:16-alpine
```

## 3. LLM 엔드포인트

BFF는 OpenAI 호환 API로 말한다. 로컬이라면 Ollama가 간단하다.

```bash
ollama pull gemma3:4b
ollama serve
```

> 💡 Windows·macOS의 Ollama 앱은 설치와 함께 백그라운드로 뜨므로 `ollama serve`가 필요 없다. 리눅스는 설치 스크립트가 systemd 서비스로 등록한다.
> 💡 BFF가 호스트에서 직접 도는 구성이라 `localhost:11434`로 붙는다 — 도커 구성과 달리 Ollama를 외부에 개방(`OLLAMA_HOST=0.0.0.0`)할 필요가 없다.

모델은 각자의 라이선스·이용약관을 따른다 — [README 라이선스](README.md#모델) 참고.

## 4. `application-local.properties`

`backend/api/src/main/resources/`에 만든다(gitignore 대상).

```properties
spring.datasource.password=devpassword

spring.ai.openai.base-url=http://localhost:11434/v1
spring.ai.openai.api-key=ollama
spring.ai.openai.chat.options.model=gemma3:4b
```

`application.properties`의 기본값이 `localhost`를 가리키므로 주소는 대부분 그대로 둔다.
`api-key`는 Ollama에선 쓰이지 않지만 비워두면 기동에 실패하니 아무 문자열이나 넣는다.
모델명은 `ollama list`에 나오는 이름을 쓴다.

## 5. BFF

**macOS · Linux**

```bash
cd backend
./gradlew :api:bootRun
```

**Windows (PowerShell)**

```powershell
cd backend
.\gradlew.bat :api:bootRun
```

빈 로컬 DB에 **최초 1회** 스키마를 만들 때만 Flyway를 켜서 띄운다 — `bootRun` 뒤에
`--args="--spring.flyway.enabled=true"`를 붙인다. 기본 프로파일에서는 꺼져 있다. 이는 개발 DB 초기화용이며,
PR의 전체 migration 적용 검증은 CI `flyway-postgres` job이 담당한다.

테스트는 `bootRun` 자리에 `test`를 넣는다.

`http://localhost:8090/actuator/health`가 `{"status":"UP"}`이면 정상이다.

## 6. 프론트엔드

`frontend/.env.example`을 `.env.local`로 복사해 채운다.

**macOS · Linux**

```bash
cd frontend
cp .env.example .env.local
openssl rand -base64 32      # NEXTAUTH_SECRET 용
```

**Windows (PowerShell)**

```powershell
cd frontend
Copy-Item .env.example .env.local
# NEXTAUTH_SECRET 용 — openssl이 없으면 아래로 대신한다
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

채울 값:

```
NEXTAUTH_URL=http://localhost:3000
NEXTAUTH_SECRET=<위에서 만든 값>
KEYCLOAK_ISSUER=http://localhost:8081/realms/app-realm
KEYCLOAK_CLIENT_ID=ogjg-client
KEYCLOAK_CLIENT_SECRET=devsecret
NEXT_PUBLIC_BFF_BASE_URL=http://localhost:8090
```

```bash
bun install
bun dev
```

`http://localhost:3000` → `devuser` / `devpass123`

검사는 `bun lint:check`, 자동 수정은 `bun lint` · `bun format`이다.

---

## 끄기

```bash
docker stop dev-keycloak dev-postgres     # 프론트·BFF는 Ctrl+C
```

컨테이너를 지우지 않으면 realm 설정과 데이터가 남아 `docker start`로 재개할 수 있다.

## 막혔을 때

- **로그인 후 첫 화면에서 `fetch failed`** — BFF가 떠 있지 않다.
- **로그인은 되는데 질문에 401** — 토큰의 `aud`에 `ogjg-client`가 없다.
- **질문에 403** — 계정에 `USER` realm role이 없다.
- **로그인 화면이 반복된다** — 프론트와 BFF가 서로 다른 Keycloak을 본다.
- **`Cannot find a Java installation ... languageVersion=17`** — JDK 17이 필요하다.
- **`gradlew` 첫 실행이 `PKIX path building failed`로 죽는다** — `JAVA_HOME`이 옛 JDK(8 등)를 가리켜 gradle 배포본을 내려받지 못하는 것이다. JDK 17을 `JAVA_HOME`으로 지정한다.
- **`ClassNotFoundException: ...GradleWorkerMain`** — 테스트 워커가 Gradle 자신의 클래스를 못 찾는 경우다. 홈 경로에 한글 등 ASCII 밖 문자가 있으면 Windows에서 나타난다. 저장소와 `GRADLE_USER_HOME`을 ASCII 경로로 옮기거나, 컨테이너 안에서 돌린다 — `docker run --rm -v "$PWD:/src:ro" -w /work eclipse-temurin:17-jdk sh -c "cp -r /src /work/p && cd /work/p && ./gradlew test --no-daemon"`

Keycloak 설정을 고쳤다면 로그아웃 후 다시 로그인한다 — 이미 발급된 토큰은 바뀌지 않는다.
