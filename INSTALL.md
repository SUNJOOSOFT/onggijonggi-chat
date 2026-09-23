# 설치 — 내 PC에서 띄워보기

`docker compose` 한 번으로 9개 컨테이너(프론트·BFF·게이트웨이·인증·DB·문서 워커·파일 저장소 3개)가 뜬다. **모델은 포함돼 있지 않다** — OpenAI 호환 엔드포인트 하나를 각자 연결한다(2단계).

작업 시간은 10분 남짓, 여기에 첫 이미지 빌드 5~15분이 더해진다.

> **성격**: 개발·평가용 구성이다. HTTPS가 아니고 자격증명이 공개값이다. 공개망에 그대로 두지 않는다(맨 아래 [알아둘 제약](#알아둘-제약)).

---

## 0. 준비물

| | 필요한 것 |
|---|---|
| 메모리 | 여유 8GB 이상 |
| 디스크 | 10GB 이상 |
| 모델 | 상용 API 키 하나 (2단계에서 무료로 발급받는다) |
| 포트 | `3010` `8090` `8081` `5442` `4000` `6379` 가 비어 있어야 한다 |

아래에서 자기 OS를 펼쳐 도커를 설치한다.

<details>
<summary><b>Windows</b> — Docker Desktop</summary>

[Docker Desktop](https://www.docker.com/products/docker-desktop/)을 설치한다. WSL2 백엔드를 쓰도록 설정한다(설치 시 기본값).

</details>

<details>
<summary><b>macOS</b> — Docker Desktop</summary>

[Docker Desktop](https://www.docker.com/products/docker-desktop/)을 설치한다. Apple Silicon·Intel용 설치본이 다르니 맞는 쪽을 받는다.

> ⚠️ **메모리 할당을 확인한다.** Docker Desktop → Settings → Resources에서 Memory가 8GB 이상인지 본다. 기본값이 낮으면 프론트 빌드가 조용히 실패한다.

</details>

<details>
<summary><b>Linux</b> — Docker Engine</summary>

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
```

`usermod` 뒤에는 **로그아웃했다 다시 로그인해야** 적용된다. 그 전까지는 모든 `docker` 명령에 `sudo`를 붙인다.

</details>

설치 확인:

```bash
docker compose version
```

답하지 않으면 compose 플러그인이 빠진 것이다 — 리눅스라면 `sudo apt install docker-compose-plugin`(데비안 계열) 등으로 채운다.

---

## 1. 클론 · 설정 복사

**macOS · Linux**

```bash
git clone https://github.com/SUNJOOSOFT/onggijonggi-chat.git
cd onggijonggi-chat/infra
cp .env.example .env
```

**Windows (PowerShell)**

```powershell
git clone https://github.com/SUNJOOSOFT/onggijonggi-chat.git
cd onggijonggi-chat\infra
Copy-Item .env.example .env
```

> ⚠️ **`.env`의 자격증명은 저장소에 공개된 고정값이다.** 로그인 `appuser`/`appuser`, Keycloak 관리자 `admin`/`admin`. 혼자 시험하는 범위를 넘어선다면 반드시 바꾼다.

> 💡 `.env.example`은 **Gemini에 붙는 기본 템플릿**이다. Ollama나 사내 서버를 쓸 거라면 나중에 [다른 템플릿](INSTALL_models.md)으로 갈아탄다 — 지금은 그냥 복사하고 넘어가도 된다.

<details>
<summary>접속 주소가 어떻게 잡혀 있는지 (안 읽어도 된다)</summary>

`.env` 맨 아래 네 줄이 브라우저가 쓰는 주소다.

```
NEXTAUTH_URL=http://localhost:3010
PUBLIC_FRONTEND_URL=http://localhost:3010
PUBLIC_BFF_URL=http://localhost:8090
PUBLIC_KEYCLOAK_URL=http://localhost:8081
```

네 줄 모두 **브라우저가 보는 주소**라 `localhost`면 된다.

서버끼리 주고받는 구간은 주소가 따로다. 컨테이너 안에서 `localhost`는 그 컨테이너 자신을 가리키므로, nextjs가 Keycloak에 토큰을 받으러 갈 때는 도커 내부 주소(`http://keycloak:8080`)를 쓴다 — compose의 `KEYCLOAK_INTERNAL_ISSUER`가 그 값이다.

**같은 네트워크의 다른 기기(폰 등)에서도 열고 싶다면** 네 줄을 모두 이 PC의 IP로 바꾼다(`http://192.168.0.15:3010` 식). 단 **3단계로 넘어가기 전에** 바꿔야 한다 — 로그인 설정은 최초 기동 때 이 값으로 한 번만 만들어진다.

</details>

---

## 2. 모델 연결

**Google Gemini는 무료 등급이 있어 결제 없이 바로 써볼 수 있다.** 이 문서는 그 기준으로 안내한다.

[aistudio.google.com/apikey](https://aistudio.google.com/apikey)에서 키를 발급받아(구글 계정만 있으면 된다) `.env`의 **한 줄만** 채운다.

```
LLM_API_KEY=
```

이걸로 끝이다. 설정 파일은 손댈 것이 없다 — `.env.example`이 Gemini 하나를 연결해 둔 상태다.

> 💡 **다른 모델을 쓰려면** → [INSTALL_models.md](INSTALL_models.md). Claude·OpenAI를 함께 열거나, 내 PC의 Ollama·사내 서버에 붙이는 법이 있다. `.env` 템플릿을 갈아 복사하는 것이 전부다.

---

## 3. 기동

```bash
docker compose up -d --build
```

첫 실행은 이미지 내려받기와 프론트·BFF 빌드까지 하느라 **5~15분** 걸린다. 진행 상황은 다른 창에서:

```bash
docker compose ps
```

**✅ 성공**: 9개 서비스가 모두 `healthy`가 된다. `nextjs`는 `bff` → `keycloak` → `postgres` 순으로 기다렸다 뜨므로 가장 늦다.

> 메모리가 모자라 빌드가 죽는다면(`Fail extracting tarball` 등) 나눠서 돌린다:
> `docker compose build bff` → `docker compose build nextjs` → `docker compose up -d`

<details>
<summary>로그인 설정이 만들어졌는지 직접 확인하려면 (안 읽어도 된다)</summary>

**macOS · Linux**

```bash
docker compose logs keycloak | grep imported
```

**Windows (PowerShell)**

```powershell
docker compose logs keycloak | Select-String "imported"
```

**✅ 성공**: `Realm 'app-realm' imported` — 로그인 설정(realm·client·계정)이 `.env` 값으로 자동 생성됐다는 뜻이다. **최초 기동 때 한 번만** 만들어진다. 4단계에서 로그인이 되면 어차피 확인되는 내용이다.

</details>

---

## 4. 접속

브라우저에서 **<http://localhost:3010>** 을 연다. 로그인 화면으로 넘어간다.

| | |
|---|---|
| 아이디 | `appuser` |
| 비밀번호 | `appuser` |

로그인하면 채팅 화면이 나온다. 한 줄 보내서 응답이 **한 글자씩 흘러나오면** 프론트 → BFF → 게이트웨이 → 모델까지 전 구간이 붙은 것이다.

---

## 문제 해결

| 증상 | 원인과 조치 |
|---|---|
| `port is already allocated` | 그 포트를 쓰는 프로그램이 있다. 범인을 찾거나 `docker-compose.yml`의 `ports` 왼쪽 숫자를 바꾼다(바꿨다면 `.env`의 주소도 함께) → 아래 [포트 확인](#포트-확인) |
| 로그인 버튼을 누르면 **"There is a problem with the server configuration"** | nextjs가 Keycloak에 못 닿는 경우다. `docker compose logs --tail=30 nextjs`에 `Unable to connect`이 보이는지 확인한다. `.env`의 주소를 기동 후에 바꿨다면 [설정 다시 만들기](#로그인-설정-다시-만들기) |
| 로그인은 되는데 **채팅만 답이 없다** | 모델 연결 문제다. `docker compose logs --tail=50 litellm`을 본다. Ollama라면 십중팔구 `OLLAMA_HOST=0.0.0.0` 누락([INSTALL_models.md](INSTALL_models.md#내-pc의-ollama)) |
| **업데이트한 뒤부터** 채팅만 답이 없다 | 예전 `.env`를 그대로 쓰고 있어서다. 기본 구성이 읽는 키 이름이 `GEMINI_API_KEY`에서 **`LLM_API_KEY`로 바뀌었다** — `.env`에서 그 값을 `LLM_API_KEY=`로 옮겨 적고 `docker compose up -d`. 모델 목록은 키와 무관하게 뜨므로 화면상으로는 멀쩡해 보인다 |
| 채팅 요청이 **401/403** | BFF가 토큰을 거부한 것이다. `.env`의 `PUBLIC_KEYCLOAK_URL`을 기동 후에 바꾸지 않았는지 확인한다 — 바꿨다면 [설정 다시 만들기](#로그인-설정-다시-만들기) |
| `nextjs`가 계속 `starting` | 빌드 인자가 굳은 경우가 있다. `docker compose up -d --build nextjs` |
| postgres가 `initdb`에서 죽는다 | 오래된 libseccomp 호스트 이슈다. compose에 이미 `seccomp=unconfined` 우회가 들어 있으니, 그래도 죽는다면 도커를 갱신한다 |
| **(Linux)** `permission denied ... docker.sock` | `docker` 그룹 적용 전이다. 로그아웃 후 재로그인하거나 `sudo`를 붙인다 |

### 포트 확인

**macOS · Linux**

```bash
lsof -i :3010
```

**Windows (PowerShell)**

```powershell
netstat -ano | Select-String ":3010"
```

### 로그인 설정 다시 만들기

realm은 **최초 기동 때 한 번만** 만들어진다. 주소나 계정을 바꿨다면 지우고 다시 만든다.

```bash
docker compose down
docker volume rm ogjg-chat_postgres-data
docker compose up -d --build
```

> ⚠️ **로그인 계정과 대화 내용이 함께 지워진다.** `.env`의 `APP_USER`로 만들어지는 기본 계정은 자동으로 다시 생긴다.

---

## 권한 기능 켜기

팀·직급에 따라 볼 수 있는 워크스페이스가 갈리는 권한 기능(Casbin)은 **기본으로 꺼져 있다.** 켜지 않으면 지금 설명한 그대로 돈다. 개발·데모용이라 지금은 로그인한 누구나 팀·직급을 바꿀 수 있다.

**1. `infra/.env`에 두 줄을 넣고 다시 띄운다.**

```bash
SPRING_PROFILE=prod,casbin
COMPOSE_PROFILES=casbin
```

```bash
docker compose up -d --build
```

**✅ 성공**: `docker compose ps`에 `casbin`이 보이고, BFF 로그에 `Casbin 규칙 적재`가 찍힌다. 조직 구조(팀·워크스페이스·규칙)는 `infra/config/workspace-setup.yml`에서 BFF가 뜰 때 읽는다.

**2. 시험 계정과 배정을 넣는다.** 저장소 루트에서:

```bash
node scripts/casbin-demo-accounts.mjs
node scripts/import-members.mjs infra/config/demo-members.csv --apply
```

첫 줄은 `demo1`~`demo7` 계정(비밀번호는 계정 이름과 같다)을 Keycloak에 만들고, 둘째 줄은 `demo1`~`demo6`의 팀·직급을 넣는다. `demo7`은 배정하지 않은 사람을 확인하는 계정이다. 임포트는 `--apply`를 빼면 미리보기만 한다.

**3. 화면에서 확인한다.** 로그인하면 사이드바에 **권한 관리**가 생긴다(<http://localhost:3010/admin/permissions>). 사람마다 팀·직급을 바꾸면 "누가 무엇을 보나" 표가 실제 판정 결과로 바뀐다.

끄려면 두 줄을 지우고 `docker compose --profile casbin down` 뒤 다시 띄운다. 넣어둔 팀·직급은 DB에 남는다.

---

## 내리기

```bash
docker compose down       # 컨테이너만 정리 (데이터 유지)
docker compose down -v    # 볼륨까지 삭제 (계정·대화 전부 삭제)
```

---

## 알아둘 제약

- **HTTPS가 아니다.** 브라우저가 "안전하지 않음"으로 표시하는 게 정상이다. 자물쇠가 필요하면 [INSTALL_r-proxy.md](INSTALL_r-proxy.md)로 caddy를 얹는다.
- **이 PC에서만 접속된다.** 기본 주소가 `localhost` 기준이라 그렇다 — 다른 기기에서 열려면 1단계의 접힌 절을 본다.
- **자격증명이 공개값이다.** `.env.example`에 그대로 적혀 있다. 직접 정한 값으로 바꾸려면 `infra/utils/init-env.ps1`(Windows) 또는 `infra/utils/init-env.sh`(macOS·Linux)로 `.env`를 만든다 — 비밀번호 2개만 입력받고 나머지 5개는 무작위로 채운다. **최초 기동 전에** 해야 한다(이미 띄운 뒤에 바꾸면 Keycloak·DB에 저장된 값과 어긋나 로그인이 막힌다).
- **Keycloak이 개발 모드(`start-dev`)로 뜬다.** 평문 HTTP를 허용하는 구성이다.
- **DB·게이트웨이 포트가 호스트의 모든 인터페이스에 열린다**(`5442` `4000` `8090` `8081`). 신뢰할 수 없는 네트워크에 물린 PC라면 방화벽으로 막는다.

---

## 다음

- 다른 모델을 쓰려면 → [INSTALL_models.md](INSTALL_models.md) (Claude·OpenAI·Ollama·사내 서버)
- 코드를 고치려면 → [CONTRIBUTING.md](CONTRIBUTING.md) (프론트·BFF를 호스트에서 직접 띄우는 개발 구성)
- HTTPS로 쓰려면 → [INSTALL_r-proxy.md](INSTALL_r-proxy.md)
- 구조가 궁금하면 → [README.md](README.md)
