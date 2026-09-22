# onggijonggi-chat

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="frontend/public/brand/logos/onggijonggi-logo-horizontal-dark.svg">
  <img src="frontend/public/brand/logos/onggijonggi-logo-horizontal.svg" alt="옹기종기" width="320">
</picture>

**사람과 AI가 한 대화에 옹기종기.** 옹기종기는 비개발 직군을 포함한 여러 구성원과 여러 AI 에이전트가 하나의 대화 맥락을 공유하며 협업할 수 있는 환경을 지향하는 오픈소스 사내 업무용 LLM 에이전트 솔루션이다.

한 구성원이 업무 질문을 올리면 권한이 있는 동료가 정보와 판단을 보태고, 필요한 AI 에이전트가 맡은 역할에 맞게 기여한다. 뒤이어 참여하는 사람과 AI는 앞서 오간 설명과 결정을 같은 대화에서 확인하고 그 맥락을 이어받는다. 조직별 데이터와 권한의 경계는 그대로 유지한다.

**현재 공개본은** Keycloak 인증, 모델 게이트웨이, 개인별 대화 저장을 제공하는 셀프호스트 AI 채팅 기반이다. 어떤 모델을 쓸지는 설정 파일 한 곳에서 정한다 — 상용 API(OpenAI·Anthropic 등)든, 직접 띄운 로컬 모델(Ollama·vLLM)이든 같은 방식으로 붙는다.

여러 사람과 여러 AI가 하나의 대화를 공유하는 협업과 조직별 데이터·권한 격리는 **앞으로 만들어 간다** — 지금 무엇이 되고 다음이 무엇인지는 [ROADMAP](ROADMAP.md)에 있다.

- 🔐 **Keycloak(OIDC) 로그인** + JWT 검증 · 역할 기반 접근 제어 · 분당 요청 제한
- 💬 **스트리밍 채팅** — 응답이 생성되는 대로 출력, 대화 이력은 DB에 저장
- 🔀 **모델 교체가 설정 한 줄** — 애플리케이션 코드를 고치지 않는다
- 🐳 **`docker compose up` 한 번**으로 9개 서비스 전체 기동

> 이 저장소는 **Community Edition**이다. 기능을 더한 Pro 버전이 별도로 있으며, 이 저장소에는 포함되지 않는다.

---

## 빠른 시작

```bash
git clone https://github.com/SUNJOOSOFT/onggijonggi-chat.git
cd onggijonggi-chat/infra
cp .env.example .env                           # ① 주소·자격증명은 채워져 있다 — 그대로 둔다
# ② 모델 연결 — .env 의 LLM_API_KEY 한 줄, 무료 발급 (INSTALL.md 2단계)
docker compose up -d --build                   # ③ 전체 기동 (첫 빌드 5~15분)
```

→ **<http://localhost:3010>** 에서 `appuser` / `appuser` 로 로그인.

⚠️ **모델은 포함돼 있지 않다.** ②에서 API 키 하나를 연결한다(Gemini는 무료로 발급된다). Claude·OpenAI를 함께 쓰거나 로컬 Ollama·사내 서버에 붙이려면 [INSTALL_models.md](INSTALL_models.md).

⚠️ **`.env.example`의 자격증명은 공개된 고정값이다** — 로그인 `appuser`/`appuser`, 관리자 `admin`/`admin`. 바로 띄워보라고 채워둔 값이니, 혼자 시험하는 범위를 넘으면 반드시 바꾼다.

⚠️ **기본 구성은 HTTPS가 아니고, 도커를 돌린 그 PC에서만 접속된다.** 같은 네트워크의 다른 기기에서도 열려면 `.env`의 주소 네 줄을 이 PC의 IP로 바꾼다 — **최초 기동 전에** 해야 한다. 로그인 설정(realm·client·계정)은 처음 뜰 때 `.env` 값으로 **한 번만** 만들어지기 때문이다.

👉 **[설치 가이드 — INSTALL.md](INSTALL.md)** — 준비물부터 문제 해결까지 Windows·macOS·Linux 절차를 담았다.

**필요한 것**: Docker(Windows·macOS는 Docker Desktop, Linux는 Docker Engine), 메모리 8GB+, 디스크 10GB+, 그리고 **LLM 하나**(상용 API 키 또는 Ollama 같은 OpenAI 호환 엔드포인트).

---

## 구성

```
브라우저 → nextjs(3010) → bff(8090) → litellm(4000) → 사용자가 고른 LLM
        ↘ keycloak(8081)           ↘ postgres(5442)
```

선택 사항으로 **caddy 리버스 프록시**를 얹으면 `app.localhost`·`api.localhost`·`auth.localhost`를 각각 nextjs·bff·keycloak으로 넘기고 HTTPS를 종단한다. `.localhost`는 RFC 6761 예약 이름이라 브라우저가 알아서 127.0.0.1로 해석한다 — hosts 파일 등록이 필요 없다. caddy 서비스는 compose에서 `profiles: [caddy]`로 묶여 있어 기본 기동에는 포함되지 않는다([INSTALL_r-proxy.md](INSTALL_r-proxy.md)).

| 디렉터리 | 내용 | 스택 |
|---|---|---|
| `frontend/` | 채팅 UI · 로그인 | Next.js(App Router) · React · AI SDK |
| `backend/api/` | 실행 모듈 — HTTP·WebSocket 엔드포인트, 필터체인 | Spring Boot WebFlux · Spring AI |
| `backend/common/` | 공유 라이브러리 — 인증·사용자·대화 도메인, 마이그레이션 | JPA · Flyway |
| `infra/` | compose 오케스트레이션 · 게이트웨이 설정 | Docker Compose · LiteLLM · Keycloak · PostgreSQL |
| `worker/` | 문서 처리 워커 | (예약 — 코드 없음) |

프론트엔드는 **BFF만** 호출한다. 게이트웨이와 모델 자격증명은 브라우저에 노출되지 않는다.

---

## 문서

| 문서 | 언제 보나 |
|---|---|
| [INSTALL.md](INSTALL.md) | **처음 띄울 때.** Docker로 전체 스택 셀프 호스팅 (`http://localhost:3010`) |
| [INSTALL_models.md](INSTALL_models.md) | **기본 말고 다른 모델을 쓸 때.** Claude·OpenAI 함께 쓰기, 로컬 Ollama, 사내 서버(vLLM 등) |
| [INSTALL_r-proxy.md](INSTALL_r-proxy.md) | **HTTPS가 필요할 때.** caddy를 얹어 `https://app.localhost`로 쓰는 방법 — INSTALL.md에서 달라지는 곳만 |
| [ROADMAP.md](ROADMAP.md) | **어디까지 되는지 알고 싶을 때.** 지금 버전의 한계와 다음 방향 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | **코드를 고칠 때.** 이슈·브랜치·커밋·PR 절차와, 프론트·BFF를 호스트에서 직접 띄우는 개발 환경 구성 |
| [`frontend/README.md`](frontend/README.md) | 프론트엔드 계층의 역할·스택과 동작 메모 |

계층 구조는 코드와 주석에서 `01·CLIENT` ~ `06·DOCUMENT` 번호로 참조한다 — 01은 `frontend/`, 02·EDGE와 03·CORE는 `backend/api/`의 `security/`·`chat/`와 `backend/common/`의 `auth/`·`chat/`, 04·DATA는 스키마·엔티티, 05·INFRA는 `infra/`, 06·DOCUMENT는 `worker/`(예약)에 해당한다.

---

## 알아둘 것

기본 구성은 **개발·평가용**이다. 그대로 공개망에 두지 않는다 — Keycloak이 개발 모드(평문 HTTP)로 뜨고, 게이트웨이·DB 포트가 호스트의 모든 인터페이스에 열린다. 자세한 목록과 대처는 [INSTALL.md — 알아둘 제약](INSTALL.md#알아둘-제약).

---

## 라이선스

Copyright 2026 [SUNJOOSOFT](http://sunjoosoft.com/). 이 저장소는 [Apache License 2.0](LICENSE)을 따른다.

### `frontend/` — Vercel ai-chatbot 포크

`frontend/`는 Vercel [`ai-chatbot`](https://github.com/vercel/ai-chatbot)(Apache-2.0) 템플릿에서 시작했다. 원본 저작권 고지는 [`frontend/LICENSE`](frontend/LICENSE)에 그대로 유지한다.

**원본에서 다음을 변경했다**(Apache-2.0 §4(b) 고지):

- **제거** — 블록(artifacts) 에디터 일체(코드·이미지·텍스트 블록, `editor`·`console`·`weather`·`suggested-actions` 등), 그리고 브라우저가 모델을 직접 호출하던 `app/(chat)/api/chat/route.ts`
- **추가** — Keycloak OIDC 인증(`auth.ts`·`middleware.ts`·next-auth 라우트), BFF 전용 호출 계층(`lib/api/`), 다중 세션 스토어(`lib/store/chat-sessions.ts`), 근거 인용 패널(`components/citations-panel.tsx`), LaTeX 수식 렌더링, 테마 토글, Docker 패키징
- **변경** — 모델 호출 경로를 BFF 단일 진입점 경유로 바꾸고, UI 문구를 한국어로 옮겼다
- **브랜딩 자산 제거** — 원본의 OpenGraph·트위터 카드 이미지, 파비콘, README의 "Deploy with Vercel" 버튼과 템플릿 소개문을 걷어내고, 페이지 제목·설명·`metadataBase`를 이 프로젝트 값으로 바꿨다

### 폰트

`frontend/public/fonts/`의 Geist 폰트는 코드와 별개로 [SIL Open Font License 1.1](frontend/public/fonts/OFL.txt)을 따른다.

### 서드파티 고지

의존성 대부분은 MIT · Apache-2.0 · ISC · BSD 계열이다. 그 밖에 EPL 듀얼 라이선스(logback 등)와 CC-BY-4.0(`caniuse-lite`)이 있으며, **`frontend` 도커 이미지를 빌드해 배포하는 경우** Next.js가 끌어오는 `sharp`의 네이티브 라이브러리 libvips(LGPL-3.0-or-later)가 이미지에 포함된다. 조사 시점·범위와 듀얼 라이선스에서 선택한 쪽은 [`frontend/THIRD-PARTY-NOTICES.md`](frontend/THIRD-PARTY-NOTICES.md)에 적었다.

### 상표

이 프로젝트는 Vercel과 제휴·후원 관계가 없다. Vercel·Next.js를 비롯해 문서에 등장하는 제3자 이름과 상표는 모두 각 소유자의 것이며, 출처와 사용 기술을 밝히기 위해서만 쓴다.

### 모델

이 저장소는 AI 모델을 담고 있지 않다. **모델은 각자의 라이선스·이용약관을 따른다** — 예를 들어 Gemma·Llama는 표준 오픈소스 라이선스가 아니라 별도 약관과 사용 제한이 있다. 어떤 모델을 쓸지는 사용자가 정하므로, 특히 상업적으로 쓰기 전에는 해당 약관을 직접 확인해야 한다.
