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

> ⚠️ **`.env`의 자격증명은 저장소에 공개된 고정값이다.** 로그인 `appuser`/`appuser`, Keycloak 관리자 `admin`/`admin`. 특히 `KEYCLOAK_BFF_CLIENT_SECRET`은 모든 계정 목록·관리자 변경 이력을 읽는 권한이 붙은 값이라 Keycloak 포트(8081)에 닿는 누구나 쓸 수 있다. 혼자 시험하는 범위를 넘어선다면 반드시 바꾼다.

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

> ⚠️ **로그인 계정과 대화 내용이 함께 지워진다.** 대화방에 등록한 문서 원본은 문서 워커의 SeaweedFS 볼륨(`ogjg-chat_document-worker-seaweed-volume`·`ogjg-chat_document-worker-seaweed-filer`)에 따로 남으므로, 함께 지우려면 이 두 볼륨도 지운다. `.env`의 `APP_USER`로 만들어지는 기본 계정은 자동으로 다시 생긴다.

---

## 권한 기능 켜기

팀·직급에 따라 볼 수 있는 워크스페이스가 갈리는 권한 기능(Casbin)은 **기본으로 꺼져 있다.** 켜지 않으면 지금 설명한 그대로 돈다. 꺼져 있어도 모든 대화는 기본 고객사 하나의 공용 공간에 놓인다(`infra/config/workspace-setup.default.yml`을 BFF가 뜰 때 읽는다). 사람의 팀·직급은 우리 DB에 두지 않는다 — `infra/config/members/members.csv`(`username,team,rank`)에 적고, BFF가 casbin-server에 적재한다. 권한 관리 화면은 Keycloak `PLATFORM_ADMIN` 역할이 있는 계정만 열 수 있고, `.env`의 `APP_USER` 계정이 이 역할을 가진다.

> 이 저장소를 이미 한 번 띄웠다면 Keycloak realm은 처음 만들 때만 가져오므로 `PLATFORM_ADMIN` 역할이 없다. Keycloak 관리 콘솔에서 realm 역할 `PLATFORM_ADMIN`을 만들어 `APP_USER`에 붙이거나, 계정·대화가 지워져도 되면 `docker compose down -v` 뒤 다시 띄운다.

**1. `infra/.env`에 두 줄을 넣고 다시 띄운다.**

```bash
SPRING_PROFILE=prod,casbin
COMPOSE_PROFILES=casbin
```

`casbin`을 `prod` **뒤에** 적어야 한다 — 그러면 기본 조직 구조(고객사 `ogjg` 하나) 대신 팀·워크스페이스·규칙이 있는 `workspace-setup.yml`을 읽는다. 두 파일이 같은 고객사(`ogjg`)를 가리키므로 바꿔도 고객사가 둘로 늘지 않는다. `workspace-setup.yml`의 `tnn_key: ogjg`를 바꾸지 않는다 — 바꾸면 고객사가 둘이 되어 권한 기능이 꺼진 배포는 새 대화를 만들 수 없고(503) 기존 대화 절체도 멈춘다.

```bash
docker compose up -d --build
```

**✅ 성공**: `docker compose ps`에 `casbin`이 보이고, BFF 로그에 `Casbin 규칙 적재`와 `사람 속성 적재`가 찍힌다. 조직 구조(팀·워크스페이스·규칙)는 `infra/config/workspace-setup.yml`에서 BFF가 뜰 때 읽고, 사람의 팀·직급은 `infra/config/members/members.csv`에서 적재할 때마다 읽는다.

**2. 시험 계정을 만들고 casbin을 재시작한다.** 저장소 루트에서:

```bash
node scripts/casbin-demo-accounts.mjs
docker compose -f infra/docker-compose.yml restart casbin
```

첫 줄은 `demo1`~`demo7` 계정(비밀번호는 계정 이름과 같다)을 Keycloak에 만든다. 팀·직급은 `infra/config/members/members.csv`에 이미 적혀 있다(`demo1`~`demo6`, 그리고 `APP_USER` 기본값인 `appuser`). BFF는 적재할 때 아이디를 Keycloak 계정에 맞추는데, 그때 Keycloak에 없던 아이디는 건너뛴다 — 계정을 BFF보다 늦게 만들었으므로 둘째 줄로 casbin을 재시작해 다시 적재하게 한다(BFF 로그의 `사람 속성 적재: N명, 건너뜀 [...]`에서 건너뛴 아이디가 없는지 본다). `demo7`은 배정하지 않은 사람을 확인하는 계정이다 — 권한 기능이 켜져 있으면 팀·직급이 없는 사람은 1:1 채팅도 쓸 수 없다. `appuser`는 `free` 팀(1:1·공용 공간·전사 공지만)으로 들어 있다.

**3. 화면에서 확인한다.** `APP_USER`로 로그인하면 사이드바에 **권한 관리**가 생긴다(`demo` 계정은 일반 사용자라 메뉴가 없다)(<http://localhost:3010/admin/permissions>). 사람별 팀·직급과 "누가 무엇을 보나" 표(실제 판정 결과)를 볼 수 있다. 팀·직급은 화면에서 바꾸지 않는다.

**팀·직급을 바꿀 때**: `infra/config/members/members.csv`를 고치고 `docker compose -f infra/docker-compose.yml restart casbin`을 한다. 다음 판정 때 BFF가 파일을 다시 읽어 적재하고, 팀·직급이 바뀌거나 빠진 사람의 협업방 구독과 진행 중인 협업방 AI 응답을 바로 거둔다(다시 들어오면 새 팀·직급으로 판정한다). BFF 로그의 `사람 속성 적재`로 반영을 확인한다. 파일이 틀리면(없는 팀·직급, 같은 아이디 두 번 등) 적재하지 않고 BFF 로그에 틀린 줄이 모두 찍힌다. casbin을 재시작한 뒤라면 남은 적재가 없어 **모든 판정을 거부**한다 — 고친 뒤 다시 casbin을 재시작한다. (파일이 틀린 채로 권한 관리 화면에서 규칙을 바꾸면 이전 적재가 남은 채 그 고객사의 판정과 관리 변경만 막히고, 파일을 고칠 때까지 몇 초~30초 간격으로 다시 시도한다.) 이미 있던 배포에서 올리면 예전에 DB에 넣어 둔 배정은 마이그레이션으로 지워지므로 같은 내용을 이 파일에 옮겨 적는다.

끄려면 두 줄을 지우고 `docker compose --profile casbin down` 뒤 다시 띄운다. 단, 기존 대화 절체를 마치고 완료 표지가 기록된 DB에서는 끌 수 없다(아래「v0.2에서 올릴 때」).

---

## 방 문서 검색 준비 켜기 (ETL)

대화방에 등록한 문서를 검색에 쓰려면 원본에서 글자를 뽑아 잘게 나누고(청크) 임베딩해 Elasticsearch에 넣어야 한다. 이 일은 BFF와 따로 도는 ETL 워커(`etl` 서비스)가 한다. **기본으로 꺼져 있다** — 꺼져 있어도 문서 등록·고정·원본 열람은 되고, 문서는 "처리 대기"에 머문다.

**1. `infra/.env`에 세 줄을 넣고 다시 띄운다.**

```bash
COMPOSE_PROFILES=elasticsearch
EMBEDDING_URL=http://<임베딩 서버 주소>:<포트>
EMBEDDING_MODEL=bge-m3
```

다른 프로필과 함께 쓰면 쉼표로 잇는다(예: `COMPOSE_PROFILES=casbin,elasticsearch`). `EMBEDDING_URL`은 OpenAI 호환 `/v1/embeddings`를 제공하는 사내 임베딩 서버 주소이고, 모델은 1024차원 벡터를 내야 한다(bge-m3). 비워 두면 ETL 워커가 떠도 문서를 처리하지 않고 경고만 남긴다.

```bash
docker compose up -d --build
```

**✅ 성공**: `docker compose ps`에 `elasticsearch`와 `etl`이 보이고, `docker compose logs etl`에 `문서 처리 스레드 2개 시작`이 찍힌다. 대화방에 문서를 등록하면 상태가 "처리 대기" → "처리 중" → "검색 준비 완료"로 바뀐다. 이미 등록돼 있던 "처리 대기" 문서도 이어서 처리한다.

시작 로그 대신 `문서 처리를 시작하지 않는다`나 `문서 처리가 꺼져 있다`(5분마다)가 찍히면 `EMBEDDING_URL`이 비었거나 임베딩 모델의 벡터 차원이 1024가 아닌 것이다. 이때 컨테이너는 떠 있어 `docker compose ps`만으로는 알 수 없다.

처리에 실패하면 "처리 실패"로 남고, 올린 사람이나 방장이 "다시 처리"로 보관된 원본을 다시 처리할 수 있다. 원인은 `docker compose logs etl`의 `문서 처리 실패(사유 코드)`에 있다. 임베딩 서버·Elasticsearch가 잠시 끊기거나 내부 key가 어긋나면 간격을 두고(30초·2분·10분·30분) 다시 시도한다. 약 40분 넘게 끊기면 "처리 실패"로 남으니, 복구된 뒤 "다시 처리"를 누른다.

**알아둘 것**
- 메모리: Elasticsearch가 `ES_HEAP`(기본 1g)에 더해 여유를 쓰고, ETL은 컨테이너 메모리의 절반까지 힙을 쓴다. 두 서비스를 켜면 2~3GB 정도를 더 잡는다.
- 리눅스 호스트에서 Elasticsearch가 `max virtual memory areas vm.max_map_count [65530] is too low`로 뜨지 않으면 `sudo sysctl -w vm.max_map_count=262144`를 한다(재부팅 뒤에도 유지하려면 `/etc/sysctl.conf`에 적는다).
- 배포(재기동) 때 처리 중이던 문서는 다음 기동에서 이어서 처리한다.
- 검색: BFF가 같은 `EMBEDDING_URL`로 질문을 임베딩해 현재 방의 고정·검색 준비 완료 문서 안에서 찾는다. 채팅 답변에는 아직 붙지 않았고(후속), `POST /api/threads/{방 ID}/documents/search`(본문 `{"question": "..."}`)로 무엇이 찾아지는지 확인할 수 있다. 다른 API처럼 로그인 토큰(`Authorization: Bearer ...`)이 필요하고, 그 방 참여자만 부를 수 있다(아니면 401·404). 응답 `status`는 `FOUND`(근거 있음)·`NO_EVIDENCE`(고정·검색 준비 완료 문서가 없거나 질문에 맞는 내용이 없음)·`UNAVAILABLE`(검색을 끝내지 못함 — 장애도 200으로 돌려준다)이고, `reason`이 그 사유다(`MODEL_MISMATCH`는 임베딩 모델을 바꾼 뒤 이전 모델로 처리된 문서가 방에 하나라도 있으면 그 방 검색 전체에 나온다. 바꾼 설정으로 ETL을 다시 띄우면 이전 모델 문서를 자동으로 다시 처리하고(아래 "다시 만들기"), 끝나면 풀린다). 이 확인 API를 끄려면 `.env`에 `RAG_SEARCH_API_ENABLED=false`를 넣는다(404가 된다). 값은 `true`·`false`만 쓴다 — 다른 값이면 BFF가 뜨지 않는다.
- 후속 질문("그럼 그거는?")은 검색 전에 대화 모델로 검색 문장을 다시 쓴다. 기본은 그 대화에 쓰는 모델이라 새로 외부로 나가는 경로는 없다. **운영에서는 사내 모델로 고정하기를 권한다** — `.env`에 `RAG_REWRITE_MODEL=<LiteLLM 모델 이름>`. 비워 두면 사용자가 고른 모델(외부 공급자일 수 있다)로 다시 쓰므로 비용과 외부 전송이 그 모델을 따른다. 추론(thinking)을 하는 모델은 출력 한도(512토큰)를 추론에 써서 결과가 잘리면 다시 쓰기를 버리고 질문 그대로 검색한다(BFF 로그 `출력 한도에서 잘림`) — 추론하지 않는 모델을 고른다.
- 검색 인덱스는 버전 이름(`thr_doc_chunk_v2`)과 별칭(`thr_doc_chunk`)으로 나뉜다. 이전 버전(`thr_doc_chunk_v1`)이 있는 서버에 새 버전을 배포하면 ETL이 기동하자마자 청크를 새 인덱스로 옮기고 별칭을 넘긴다(재처리·재임베딩 없음, 청크가 많으면 몇 분 걸린다). 끝나면 `docker compose logs etl`에 `검색 인덱스를 옮겼다`가 찍힌다. 이전 인덱스는 남겨 두므로 **아래 두 가지를 확인한 뒤에만**, 그리고 확인되면 미루지 말고 지운다 — v1에는 이전 뒤에 지운 문서도 본문째 남는다(검색에는 나오지 않지만 ES 볼륨·스냅샷에 남는다) — 별칭이 아직 v1을 가리킬 때 지우면 청크가 모두 사라져, 원본에서 다시 만들(아래 자동 복구) 때까지 검색이 안 된다.

  ```bash
  curl -s http://127.0.0.1:9200/_alias/thr_doc_chunk        # 결과에 thr_doc_chunk_v2만 있어야 한다
  curl -s http://127.0.0.1:9200/thr_doc_chunk_v1/_count     # 두 count가 같아야 한다
  curl -s http://127.0.0.1:9200/thr_doc_chunk_v2/_count
  curl -X DELETE http://127.0.0.1:9200/thr_doc_chunk_v1     # 위가 모두 맞을 때만
  ```
- **다시 만들기(자동)**: ETL은 "검색 준비 완료" 문서의 검색 조각을 스스로 다시 만든다. 그동안 문서는 "검색 준비 완료" 그대로이고 이전 조각으로 계속 검색되며, 새 조각이 다 만들어지는 순간 바뀐다. 사용자가 새로 올린 문서와 조각이 사라진 문서를 먼저 처리하고(설정 변경·운영자 다시 만들기는 그 뒤), 처리량은 평소 동시 처리 수(2) 그대로다.
  - **조각이 사라졌을 때**: Elasticsearch 볼륨 삭제·서버 이전 등으로 조각이 없어지면 10분마다 하는 대조에서 찾아 원본에서 다시 만든다(`docker compose logs etl`의 `검색 조각이 빠진 문서 N건을 다시 만든다`). 복구가 끝나기 전에는 검색이 "근거 없음"(`NO_EVIDENCE`)으로 나온다. 원본까지 없거나 손상돼 다시 만들 수 없는 문서는 "처리 실패"가 된다(고정돼 있으면 화면에 "답변에 쓰이지 않습니다"가 함께 보인다). 임베딩 서버·Elasticsearch가 오래 끊기거나 설정 실수로 거절해 실패하면 "처리 실패"로 바꾸지 않고, 같은 문서는 한 시간에 한 번씩 다시 해 본다. Elasticsearch가 응답하지 않는 동안에는 대조하지 않는다.
  - **설정을 바꿨을 때**: 임베딩 모델·차원(`EMBEDDING_MODEL` 등)이나 청킹 설정을 바꿔 ETL을 다시 띄우면, 이전 설정으로 처리된 문서를 기동 30초 뒤부터 다시 만든다(`처리 설정이 바뀐 문서 N건을 다시 만든다`). 다시 만들기에 실패한 문서는 이전 설정 조각으로 계속 검색되고(임베딩 모델을 바꾼 경우 그 방은 `MODEL_MISMATCH`), ETL 재기동이나 운영자 요청(`OUTDATED`) 때 다시 시도한다.
- **다시 만들기(운영자)**: `PLATFORM_ADMIN` 계정 토큰으로 `POST /api/platform/rag/rebuilds`(본문 `{"scope":"ALL"}` 전체 또는 `{"scope":"OUTDATED"}` 설정이 바뀐 문서만)를 부르면 ETL이 30초 안에 대상을 골라 다시 만든다. ETL의 문서 처리가 꺼져 있거나(임베딩 주소 없음 등) 검색 인덱스 준비·이전이 끝나지 않았으면 요청은 `PENDING`으로 기다리고, 그동안 새 요청은 409다 — GET에서 `status`가 계속 `PENDING`(그리고 `targets`가 비어 있음)이면 ETL이 아직 요청을 집지 않은 것이다. `docker compose ps etl`로 ETL이 떠 있는지 보고, `docker compose logs etl`에서 `다시 만들기를 미룬다`(인덱스 준비·이전 중, 대기 회차 1000건 초과)나 `문서 처리가 꺼져 있다`를 찾는다. `OUTDATED`는 설정이 바뀐 문서가 없으면 `targets`가 0으로 끝나며 정상이다.
  - **진행 확인**: 자동 복구는 `검색 조각이 빠진 문서 N건을 다시 만든다`로 시작해 문서마다 `문서 처리 완료 … kind=RECOVER`가 찍히고, 실패는 `문서 처리 실패 … kind=RECOVER`다. 대조가 끝날 때마다 `검색 조각 대조를 마쳤다`가 남는다(기본 10분 간격). `docker compose logs etl | grep -E "kind=(RECOVER|REBUILD)|대조를|다시 만들기를"`. 지금 기다리는 다시 만들기 수는 `docker compose exec postgres psql -U appuser -d appdb -c "select run_kind, status, count(*) from thr_doc_run where status in ('PENDING', 'RUNNING') group by 1, 2"`로 본다(`appuser`·`appdb`는 기본값 — `.env`의 `POSTGRES_USER`·`POSTGRES_DB`를 바꿨으면 그 값). 진행 상황은 `GET /api/platform/rag/rebuilds`(최근 10건)·`/api/platform/rag/rebuilds/{id}`의 `targets`(대상)·`remaining`(남음)·`failed`(실패)로 본다. 앞 요청이 아직 끝나지 않았으면 409(`REBUILD_IN_PROGRESS`, 문구에 진행 중 요청 ID)다.
- Elasticsearch를 예전 스냅샷으로 되돌리면 그 뒤에 지운 문서·정리된 회차의 조각이 다시 생긴다. 검색에는 나오지 않지만(검색은 살아 있는 문서의 현재 회차만 본다) 저장소에 남으므로, 되돌린 뒤 `curl -X DELETE http://127.0.0.1:9200/thr_doc_chunk_v2`로 인덱스를 비우고 자동 복구로 다시 만드는 편이 깨끗하다(문서가 많으면 그만큼 다시 임베딩한다). 태그 색인도 같다 — `curl -X DELETE http://127.0.0.1:9200/thr_doc_tag_v1`로 지우면 ETL이 다음 태깅 주기(30초 안)에 다시 만들고 DB의 태그로 채운다(다시 태깅하지 않는다).
- 문서의 한글이 자모 분해형(macOS에서 만든 파일 등)이면 이번 버전부터 조합형으로 맞춰 색인한다. 그 전에 올린 그런 문서는 키워드 검색이 맞지 않으므로 운영자 다시 만들기(`ALL`)로 한 번 다시 처리한다.
- 이전 버전 이미지로 되돌려도 `thr_doc_chunk_v2`는 지우지 않는다 — 되돌린 ETL도 별칭이 가리키는 v2에 쓴다. ETL은 한 대로 띄운다(여러 대면 인덱스 이전이 겹친다).
- 다시 만들기가 생기기 전 버전의 ETL로 되돌리면, 그 ETL은 대기 중인 다시 만들기 회차를 모두 취소한다(운영자 요청은 `COMPLETED`로 남아 성공한 것처럼 보인다). 되돌리기 전에 `GET /api/platform/rag/rebuilds`의 `remaining`이 0인지 보고, 새 버전을 다시 올린 뒤 필요하면 운영자 요청을 다시 한다. DB는 되돌리지 않는다 — 추가된 컬럼·테이블은 이전 코드가 쓰지 않는다. 자동 복구가 "처리 실패"로 바꾼 문서는 이전 화면의 "다시 처리"로 고칠 수 있고, 남은 조각은 새 버전을 다시 올리면 정리된다.

### 문서 자동 태깅 (선택)

ETL은 "검색 준비 완료"가 된 문서마다 사내 LLM으로 카테고리·핵심 키워드(최대 10개)·요약(1~2문장)을 뽑아 저장한다. 질문 표현이 본문과 달라도 문서를 찾게 하는 검색 태그 채널에 쓴다. **기본으로 꺼져 있다** — 꺼져 있어도 문서 처리·검색은 그대로다.

```dotenv
TAGGING_URL=http://<사내 LLM 서버 주소>:<포트>
TAGGING_MODEL=<그 서버의 모델 이름>
```

- `TAGGING_URL`은 OpenAI 호환 `/v1/chat/completions`를 제공하는 **사내** LLM 서버 주소다(`/v1`은 붙이지 않는다 — 붙이면 404로 모든 문서가 실패한다). 게이트웨이(LiteLLM)를 거치지 않고 직접 부르고, 문서 본문이 그대로 전달되므로 외부 공급자 주소를 넣지 않는다. 둘 중 하나라도 비면 `docker compose logs etl`에 `문서 태깅이 꺼져 있다`가, 문서 처리가 꺼져 있거나(임베딩 설정) 검색 인덱스 준비 전이면 `문서 태깅이 기다리는 중이다`가 5분마다 찍힌다.
- 태깅은 "검색 준비 완료" 뒤에 따로 돈다. 태깅이 느리거나 실패해도 문서는 이미 검색된다. 태깅 서버 장애로 실패하면 그 묶음을 멈추고 쉬었다가, 실패한 문서는 1분부터 두 배씩 늘려 최대 한 시간 간격으로 다시 해 본다. 다시 해도 같은 영구 실패(원본 없음·입력 거절 등)는 7일 뒤에 다시 한다. ETL을 다시 띄우면 지난 실패를 모두 바로 다시 시도하므로, 태깅 서버 주소·모델·카테고리를 고쳤으면 ETL을 다시 띄우면 된다. 문서 처리 결과(조각·임베딩)는 건드리지 않는다.
- 이 버전을 처음 올리면 기존 "검색 준비 완료" 문서를 차례로 태깅한다. 카테고리 목록(`.env`의 `TAGGING_CATEGORIES`에 쉼표로 잇는다. 비우면 기본 7개)·태깅 모델을 바꿔 ETL을 다시 띄우면 태그만 다시 뽑는다(다시 임베딩하지 않는다). 카테고리 순서만 바꿔도 바뀐 것으로 보고 전체를 다시 뽑는다 — 문서 수만큼 태깅 서버를 부르니 목록은 한 번에 정한다(같은 이름 중복·공백은 무시한다). 태깅 서버 주소만 바꾸면(모델 이름이 같으면) 이미 붙은 태그는 그대로 둔다. 전부 다시 뽑으려면 `delete from thr_doc_tag` 뒤 ETL을 다시 띄운다(태그 색인은 ETL이 뜰 때 DB 기준으로 맞춘다). 목록에 맞는 카테고리가 없으면 `UNCLASSIFIED`(미분류)로 남고, 다시 뽑지 않는다.
- 태그는 처리 회차마다 붙는다. 다시 만들기(설정 변경·운영자 요청·자동 복구)로 새 회차가 생기면 그 회차는 태그를 처음부터 다시 뽑고, 그때까지 그 문서는 태그 채널에 잡히지 않는다(본문 검색은 그대로). 문서가 많으면 그만큼 태깅 서버를 부른다. 태그 색인만 사라진 경우(색인 삭제·스냅샷 복원)에는 다시 태깅하지 않고 DB의 태그로 채운다.
- 태깅은 한 번에 문서 하나씩 차례로 한다(긴 문서는 덩어리 수만큼 연달아 부른다). 첫 배포 때 기존 문서가 많아 태깅 서버(대화와 같은 서버라면 대화 응답)가 느려지면 `TAGGING_URL`을 비우고 ETL을 다시 띄워 멈출 수 있다 — 다시 채우면 남은 문서부터 이어 한다. 태깅 서버가 끊기면 쉬는 간격을 점점 늘려 다시 본다.
- 진행 확인: 문서마다 `문서 태깅 완료`, 실패는 `문서 태깅 실패(사유 코드와 HTTP 상태, 다시 시도 시점)`가 찍힌다(예: `TAGGING_REJECTED HTTP 404`면 주소나 모델 이름을, 상태 없이 `TAGGING_UNAVAILABLE`이면 서버 연결을 확인한다). 로그에는 본문·태그 내용을 남기지 않는다. 상태별 수는 `docker compose exec postgres psql -U appuser -d appdb -c "select status, count(*) from thr_doc_tag group by 1"`로 본다. 현재 회차에 태그(미분류 포함)가 아직 없는 검색 준비 완료 문서 수는 `select count(*) from thr_doc d join thr_doc_run r on r.doc_id = d.id and r.run_seq = (select max(m.run_seq) from thr_doc_run m where m.doc_id = d.id and m.status = 'DONE') where d.status = 'READY' and not exists (select 1 from thr_doc_tag t where t.doc_id = d.id and t.run_seq = r.run_seq and t.status = 'DONE')`로 본다.
- 태그는 DB(`thr_doc_tag`)가 정본이고, 검색용으로 Elasticsearch `thr_doc_tag` 별칭(`thr_doc_tag_v1`)에 문서 단위로 넣는다. 문서를 지우면 태그도 함께 지운다.
- 태깅이 생기기 전 버전으로 되돌리면 태깅이 멈추고, `thr_doc_tag` 표와 `thr_doc_tag_v1` 색인은 그대로 남는다(이전 버전은 쓰지 않는다). 되돌린 동안 지운 문서와 다시 만들기로 바뀐 이전 회차의 태그(요약에 본문 내용이 들어 있다)는 다시 올려도 정리되지 않으니, 되돌린 기간이 있었다면 `delete from thr_doc_tag`와 `curl -X DELETE http://127.0.0.1:9200/thr_doc_tag_v1`로 비운다 — 새 버전이 모든 문서를 다시 태깅한다.
- **검색 태그 채널**: `.env`에 `RAG_TAG_CHANNEL_ENABLED=true`를 넣고 BFF를 다시 띄우면, 질문과 맞는 태그를 가진 문서에서 질문과 가장 가까운 조각을 근거 후보로 더한다(태그는 순위에만 더하고 거르지 않는다). 그 조각도 질문과 어느 정도 가까워야(코사인 0.4 이상) 들어간다. 태그 색인이 없거나(태깅을 켜지 않음) 장애면 1분 동안 태그 채널만 건너뛰고(그때마다 BFF 로그에 `태그 채널을 1분간 건너뛴다`) 검색은 계속된다.
  - **아직 켜지 않는 것을 권한다.** 평가 세트(가상 규정 문서 10개 — 주제가 겹치는 문서 포함, 질문 76개)에서 기본값(질문 낱말이 3개 이하면 전부, 넘으면 75%가 태그와 맞아야 함)은 결과가 바뀐 질문이 1개뿐이었다. 기준을 낮추면(40%) 22개 질문의 결과가 바뀌지만 적중은 늘지 않고(61/62 그대로) 첫 순위 적중이 줄며(53→52) 짧은 무관 질문이 근거를 얻는 경우가 늘었다(7/14, 끈 상태 6/14). 실제 방 문서로 효과를 확인한 뒤 켠다 — 평가는 `RAG_EVAL_EMBEDDING_URL`·`RAG_EVAL_LLM_URL`(사내 서버만)을 주고 `backend`에서 `gradlew :api:ragEval`을 돌리면 `backend/api/build/rag-eval/report.md`에 끔/켬 비교표가 나온다.

## Keycloak 관리 클라이언트와 권한 변경 감사

BFF는 Keycloak에서 사람 목록·표시 이름을 읽고, 누가 언제 누구에게 `PLATFORM_ADMIN`(권한 관리 화면·API를 여는 역할)을 줬는지·계정을 언제 끄고 지웠는지를 1분마다 읽어 DB에 지울 수 없는 기록으로 남긴다. 그룹 가입이나 복합 역할처럼 역할을 직접 붙이지 않고 권한을 얻는 경로와, 역할을 줄 수 있는 관리 권한(`realm-management`)도 함께 남는다. 처음 수집할 때는 그 시점에 이미 권한을 가진 사람을 한 번 기록한다.

이 조회는 **BFF 전용 관리 클라이언트 `ogjg-bff`**(`KEYCLOAK_BFF_CLIENT_ID`)로 한다. 로그인 클라이언트 `ogjg-client`의 secret은 프론트에도 있어서, 거기에 관리 권한을 붙이면 프론트 서버가 침해될 때 전체 계정 목록과 관리 이력이 함께 샌다. 그래서 로그인 클라이언트에는 서비스 계정이 없다.

**새로 띄우는 환경은 할 일이 없다.** 로그인 설정 파일(`infra/config/realm-app.json`)이 이벤트 저장, 세 클라이언트, 수집 권한을 갖춘 realm을 만든다.

**이미 띄운 환경은 한 번 전환한다.** 이 파일은 realm이 처음 만들어질 때만 적용되기 때문이다. **새 버전을 받은 뒤 이 절차 없이 `docker compose up -d`만 하면** BFF는 뜨지만 표시 이름·사람 목록·초대 검색·권한 변경 감사가 모두 동작하지 않는다(`.env`에 전용 클라이언트 값이 없다). v0.2에서 올리는 중이면 아래 1~4단계를 새 이미지를 띄우기 **전에** 한다(아래「v0.2에서 올릴 때」0단계). `http://localhost:8081`(관리 콘솔) → `app-realm`에서:

1. **Realm settings → Events → Admin events settings**에서 *Save events*와 *Include representation*을 켜고, *Expiration*을 1년(365일)으로 둔 뒤 저장한다.
2. **Clients → Create client**: Client ID `ogjg-bff`, *Client authentication* 켬, *Service accounts roles*만 켬(*Standard flow*·*Direct access grants* 끔).
3. 그 클라이언트 → **Service account roles → Assign role**에서 `realm-management`의 `view-users`·`view-events`·`view-realm`을 준다. 이벤트를 지우는 `manage-events`와, 모든 클라이언트의 secret까지 읽히는 `view-clients`는 주지 않는다.
4. 그 클라이언트 → **Credentials**의 *Client secret*을 `infra/.env`의 `KEYCLOAK_BFF_CLIENT_SECRET`에 넣고 `KEYCLOAK_BFF_CLIENT_ID=ogjg-bff`를 확인한다. 이미 새 버전이면 `docker compose up -d bff`로 BFF를 다시 만든다. 나중에 이 secret을 재발급하면 `.env`를 고치고 곧바로 `docker compose up -d bff`를 한다 — BFF는 다시 만들어야 새 값을 읽고, 그 사이(캐시한 토큰이 끝나는 몇 분 뒤부터) 이름·검색·감사가 `KEYCLOAK_ADMIN_UNAVAILABLE`로 실패한다.
5. **확인**: 먼저 BFF가 새 클라이언트를 쓰는지 본다 — `docker compose exec bff env | grep APP_KEYCLOAK_ADMIN_CLIENT_ID`가 `ogjg-bff`여야 한다(`docker-compose.override.yml`이나 셸 환경 변수로 `APP_KEYCLOAK_ADMIN_*`를 따로 줬다면 지운다 — 남아 있으면 이 확인이 예전 클라이언트로 통과하고 6단계 뒤에 끊긴다). 그다음 1분 뒤 `GET /api/platform/rbac/keycloak-audits`(PLATFORM_ADMIN)의 `collector`에서 `lastSuccessAt`이 `lastErrorAt`보다 늦고(또는 `lastErrorAt`이 비어 있고) `adminEventsEnabled`가 `true`면 정상이다. 협업방 참가자 이름도 보인다.
6. **Clients → `ogjg-client` → Settings**에서 *Service accounts roles*를 끄고 저장한다. Keycloak이 그 서비스 계정을 지우므로 붙어 있던 관리 역할도 함께 사라진다. 이 단계는 5단계를 확인한 **뒤에** 한다 — 그래야 관리 조회가 끊기는 구간이 없다.
7. **확인**: 로그인 secret으로 관리 토큰을 받을 수 없어야 한다(아래 `ogjg-client`는 `KEYCLOAK_CLIENT_ID` 값이다). 응답에 **`Client not enabled to retrieve service account`**가 나오면 정상이다. `Invalid client or Invalid client credentials`가 나오면 secret을 잘못 넣은 것이라 이 확인이 되지 않은 것이다 — 다시 한다. secret이 셸 기록에 남지 않게 입력으로 받는다. 브라우저 로그인·로그아웃은 그대로 된다.

   ```bash
   read -rs S && curl -s --data-urlencode "client_secret=$S" -d "grant_type=client_credentials&client_id=ogjg-client" http://localhost:8081/realms/app-realm/protocol/openid-connect/token; unset S
   ```
8. (예전에 만든 realm만) **Clients → `ogjg-cli`**가 있으면 지운다(*Action → Delete*). 팀·직급 CSV 스크립트용 클라이언트였는데 팀·직급을 파일(`infra/config/members/members.csv`)로 넣게 되어 더는 쓰지 않는다. realm 파일에서 뺀 것은 이미 만든 realm에 반영되지 않으므로 직접 지운다. 꺼진 채로 남겨 둬도 동작에는 문제가 없다.
9. (선택) **로그인 secret 재발급**: 6단계로 이 secret의 관리 권한은 이미 사라졌으므로 필수는 아니다. `.env.example`의 공개 기본값을 그대로 쓰거나 침해가 의심되면 `ogjg-client` → **Credentials → Regenerate** → `infra/.env`의 `KEYCLOAK_CLIENT_SECRET` 교체 → 곧바로 `docker compose up -d nextjs`(그 전까지는 새 로그인과 토큰 갱신이 실패한다). 로그인한 사용자는 다시 로그인해야 한다.

앱은 6단계를 빠뜨렸는지 알아채지 못한다 — 7단계 확인이 유일한 점검이다. 2·3단계의 역할 부여가 권한 변경 감사에 `MANAGEMENT_ROLE_GRANTED` 행으로 남는 것은 정상이다. 6단계로 로그인 서비스 계정의 역할이 함께 사라지는 것은 역할 해제가 아니라 클라이언트 설정 변경이라 역할 해제 행으로 남지 않는다.

**되돌리기**: 6단계 뒤에 이전 이미지로 돌아가야 하면 `ogjg-client` → Settings에서 *Service accounts roles*를 다시 켜고, **Service account roles → Assign role**에서 `realm-management`의 `view-users`·`view-events`·`view-realm`을 다시 준다 — 서비스 계정을 끌 때 역할도 함께 지워졌기 때문이다. 이전 compose는 `KEYCLOAK_CLIENT_SECRET`을 관리 secret으로 쓰므로 9단계로 바꿨다면 `.env` 값이 콘솔과 같은지 본다. 되돌릴 가능성이 있으면 6단계는 새 버전이 안정된 뒤에 한다.

**설정이 틀리면**: BFF는 멈추지 않고 로그인 클라이언트로 대신 조회하지도 않는다. BFF 헬스 체크(`/actuator/health`)에는 드러나지 않는다 — 알림은 기동 경고, 화면 문구, 감사 수집 상태, 로그뿐이다. 기동 로그에 경고가 한 번 남고, 권한 관리 화면·초대 검색은 "Keycloak 관리 연결 설정을 확인" 문구를, 감사 응답의 `lastError`는 `Keycloak 관리 클라이언트 인증 실패(설정 확인, 401)`(id·secret이 틀렸거나 그 클라이언트의 서비스 계정이 꺼져 있음 — Keycloak은 둘 다 401이라 콘솔에서 확인한다) 또는 `Keycloak 관리 권한 부족(…)`(역할 누락)을 보인다. `lastError`는 회복한 뒤에도 마지막 오류로 남으므로 비어 있는지로 판단하지 않는다.

**빈 secret**: `.env`에 `KEYCLOAK_BFF_CLIENT_SECRET`(또는 `KEYCLOAK_CLIENT_SECRET`)이 비어 있는 채로 realm이 처음 만들어지면, Keycloak이 빈 secret을 그대로 받아들여 누구나 빈 값으로 토큰을 받게 된다. 그래서 Keycloak은 빈 값을 아무도 모르는 무작위 값으로 채운 뒤 뜬다(로그에 `[경고]`). 그 경우 BFF의 관리 조회는 물론, `KEYCLOAK_CLIENT_SECRET`이 비었다면 **로그인도 되지 않는다** — 4단계처럼 콘솔에서 secret을 확인(또는 재발급)해 `.env`에 넣는다. `.env`는 항상 `.env.example` 복사나 `init-env`로 만든다.

**개인정보**: 이 설정으로 Keycloak은 관리자 변경의 상세(계정 생성·수정 시 이메일·이름 등)를 1년 보관한다. 앱 DB에는 상세를 옮기지 않고 계정 id·역할·그룹 경로만 영구 보존한다(권한 변경 감사가 목적이라 지우지 않는다). 기록을 지울 수 없다는 보장은 BFF가 쓰는 DB 계정이 테이블 소유자·슈퍼유저가 아닐 때만 성립한다 — 기본 compose는 같은 계정을 쓰므로, 운영에서는 migration 계정과 BFF 실행 계정을 나누길 권한다.

**운영 규칙**: Keycloak 최상위 관리자(master realm) 계정은 설치 담당자 것만 두고 늘리지 않는다. 이 계정의 변경은 수집하지 않는다(수집하려면 BFF에 master realm 권한을 줘야 해 오히려 권한이 커진다). 일상적인 역할·계정 관리는 `app-realm` 안에서 한다 — 그래야 모두 기록된다.

---

## v0.2에서 올릴 때 (대화가 이미 있을 때)

새 버전은 모든 대화를 고객사(Tenant)의 공용 공간에 귀속시키는 스키마 변경을 포함한다. **대화가 이미 있는데 고객사가 아직 없으면** 서버가 뜨면서 실행하는 Flyway가 `thread cutover requires exactly one ACTIVE tenant`로 멈추고 BFF가 기동하지 못한다. 이 migration은 통째로 되돌려져 대화 데이터는 그대로지만, 그 앞의 v0.3 migration은 이미 적용된 상태로 남고 BFF는 뜨지 못한다. 고객사는 BFF가 뜬 뒤에 만들어지니, 다음 순서로 올린다.

**0. Keycloak을 먼저 준비한다.** v0.2가 도는 채로「Keycloak 관리 클라이언트와 권한 변경 감사」의 1~4단계(admin event 켜기, `ogjg-bff` 클라이언트와 역할, `.env`의 `KEYCLOAK_BFF_CLIENT_ID`·`KEYCLOAK_BFF_CLIENT_SECRET`)를 한다. v0.2는 이 값을 쓰지 않아 영향이 없고, 새 BFF는 뜨자마자 이 클라이언트로 조회한다. 그 절의 5~7단계(확인 뒤 로그인 서비스 계정 끄기)는 아래 3단계까지 마쳐 BFF가 새 이미지로 뜨고 `collector.lastSuccessAt`이 갱신된 뒤에 한다. 0단계 없이 새 이미지를 띄우면 BFF의 Keycloak 관리 기능이 동작하지 않는다.

**1. 쓰기를 멈춘다.** 올리는 동안 사용자가 대화를 만들지 않게 한다(점검 시간).

**2. 절체 migration 앞까지만 적용해 새 이미지를 한 번 띄운다.** v0.2 DB에는 고객사 테이블이 아직 없으므로 Flyway를 완전히 끄면 안 된다 — 끄면 고객사를 만들 수 없다. `infra/.env`에 한 줄을 넣고 BFF만 다시 띄운다.

```bash
SPRING_FLYWAY_TARGET=20260929055051721
```

```bash
docker compose up -d --build bff
```

**✅ 성공**: BFF 로그에 `RBAC bootstrap 완료`가 찍히고 처리된 Tenant에 `ogjg`가 보인다. 기본 고객사(`ogjg`)와 공용 공간이 만들어졌다. 대화는 아직 그대로다.

**3. Flyway를 끝까지 적용해 띄운다.** 권한 판정(`prod,casbin`)을 켤 운영 DB라면 이 단계 전에 PLATFORM_ADMIN 계정으로 `POST /api/platform/rbac/cutover-validation`을 호출해 응답의 `failures`가 비어 있는지 확인한다(팀 배정 누락, 공용 공간 밖 대화 등을 알려 준다). 팀 배정은 `infra/config/members/members.csv`에서 오므로, 대화에 참여한 활성 계정을 먼저 이 파일에 적어 둔다. 사람 속성이 아직 적재되지 않았으면 검증은 503으로 끝난다. 그다음 2단계에서 넣은 줄을 지우고(지우지 않으면 이후 migration이 조용히 적용되지 않는다) 다시 띄우면, 이번엔 migration이 기존 대화를 공용 공간에 놓고 스키마를 마무리한다.

```bash
docker compose up -d bff
```

**✅ 성공**: BFF가 정상 기동한다. 기존 대화가 모두 그대로 열린다.

권한 기능을 켜서 쓰던 배포라면 이미 고객사가 있으므로 Tenant를 먼저 만드는 이 절차는 필요 없다. 공용 공간이 정확히 하나가 아니거나, 고객사가 둘 이상이거나, 1:1 대화가 공용 공간 밖에 있으면 migration은 추측하지 않고 멈춘다. 협업방은 같은 고객사의 활성 비ROOT 공간에 이미 배치돼 있으면 그 위치를 보존하고, 미배치 방만 공용 공간에 놓는다. 다른 고객사·없는 공간·비활성 공간·ROOT에 배치된 협업방은 중단 사유다. 제약을 거는 migration은 다른 트랜잭션이 잠금을 10초 넘게 쥐고 있으면 실패하고 통째로 되돌려진다 — 쓰기를 멈춘 상태에서 같은 명령을 다시 실행한다.

**되돌릴 수 없다는 점.** migration은 단계별로 따로 커밋되므로 중간 단계가 실패하면 DB가 일부만 바뀐 채 멈출 수 있다. 적용된 migration은 고치지 않고 새 migration으로 앞으로 고친다. 올리기 전에 DB를 백업한다.

**완료 표지.** 이 절차 자체는 표지를 기록하지 않는다. 권한 판정을 켠 운영 DB에서 절체를 마친 운영자가 사후 검증과 `enforce=true` 인가 확인 뒤 `insert into ctv (id, tnn_id) values (1, '<검증한 고객사 id>')`로 직접 기록한다. 표지는 수정·삭제·TRUNCATE가 거부되고, 표지가 있는 DB는 `app.rbac.enforce=false`로 기동하지 못하므로 그 뒤에는 `SPRING_PROFILE=prod,casbin`과 `COMPOSE_PROFILES=casbin`을 함께 유지해야 한다. 표지가 없는 기본 배포에는 이 제한이 없다.

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
- **자격증명이 공개값이다.** `.env.example`에 그대로 적혀 있다. 직접 정한 값으로 바꾸려면 `infra/utils/init-env.ps1`(Windows) 또는 `infra/utils/init-env.sh`(macOS·Linux)로 `.env`를 만든다 — 비밀번호 2개만 입력받고 나머지 6개는 무작위로 채운다. **최초 기동 전에** 해야 한다(이미 띄운 뒤에 바꾸면 Keycloak·DB에 저장된 값과 어긋나 로그인이 막힌다).
- **Keycloak이 개발 모드(`start-dev`)로 뜬다.** 평문 HTTP를 허용하는 구성이다.
- **DB·게이트웨이 포트가 호스트의 모든 인터페이스에 열린다**(`5442` `4000` `8090` `8081`). 신뢰할 수 없는 네트워크에 물린 PC라면 방화벽으로 막는다.

---

## 다음

- 다른 모델을 쓰려면 → [INSTALL_models.md](INSTALL_models.md) (Claude·OpenAI·Ollama·사내 서버)
- 코드를 고치려면 → [CONTRIBUTING.md](CONTRIBUTING.md) (프론트·BFF를 호스트에서 직접 띄우는 개발 구성)
- HTTPS로 쓰려면 → [INSTALL_r-proxy.md](INSTALL_r-proxy.md)
- 구조가 궁금하면 → [README.md](README.md)
