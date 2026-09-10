# 데이터 축약 사전 (생성물 — 직접 편집 금지)

> `scripts/glossary/words.md`에서 생성됩니다. 고칠 것이 있으면 그 파일을 고치고 `node scripts/build-glossary.mjs`를 다시 실행하세요.

DB 테이블·컬럼과 ES 필드 이름은 **snake_case**로 쓰고, `_`로 분해한 각 토큰이 아래 축약어여야 합니다.
조합은 자유이며 등재 대상이 아닙니다 — `mbr`와 `id`가 있으면 `mbr_id`는 그대로 성립합니다.

| 축약 | 원형 | 뜻 | 출처 | 비고 |
|---|---|---|---|---|
| `acc` | `access` | 접근 | 알고리즘 | - |
| `ans` | `answer` | 답변 | 알고리즘 | - |
| `app` | `app` | 애플리케이션 | 알고리즘 | - |
| `archived` | `archived` | 보관됨 | 예외 | 관용 유지 — `archived_at` 대칭, 기존 `created`/`updated`/`deleted`와 동일 패턴 |
| `at` | `at` | 시점 | 알고리즘 | - |
| `att` | `attempt` | 시도 | 알고리즘 | - |
| `adt` | `audit` | 감사 | 알고리즘 | - |
| `ath` | `author` | 작성자 | 알고리즘 | - |
| `by` | `by` | 주체 | 알고리즘 | - |
| `cnc` | `cancelled` | 취소됨 | 알고리즘 | - |
| `chat` | `chat` | 대화 | 알고리즘 | - |
| `chunk` | `chunk` | 조각 | 예외 | 알고리즘 `chn`은 의미가 불명확 |
| `chnk` | `chunking` | 조각화 | 예외 | `chunk`와 구분(알고리즘은 둘 다 `chn`) |
| `completed` | `completed` | 완료됨 | 예외 | 알고리즘 결과 `cmp`는 compare·component와 혼동. `completed_at` 대칭, 기존 `created`/`updated`/`deleted`/`locked`/`archived`/`inactive`/`ended`와 동일 패턴 |
| `content` | `content` | 본문 | 예외 | 알고리즘 `cnt`는 count로 통용돼 혼동 |
| `cnt` | `count` | 개수 | 알고리즘 | - |
| `created` | `created` | 생성됨 | 예외 | 관용 — `created_at`은 범용 관례라 원형 유지 |
| `cur` | `current` | - | 예외 | 관용어 — 알고리즘 결과 `crr`이 부자연스러움 |
| `crs` | `cursor` | 커서 | 알고리즘 | - |
| `deleted` | `deleted` | - | 예외 | 관용 유지 — `deleted_at`/`deleted_by` 대칭, 기존 `created`/`updated`/`indexed`/`uploaded`와 동일 패턴 |
| `dnd` | `denied` | 거부됨 | 알고리즘 | - |
| `dept` | `department` | - | 예외 | 관용어 — 알고리즘 결과 `dpr`이 불명확 |
| `dim` | `dim` | 차원 | 알고리즘 | - |
| `drc` | `direct` | 1:1 | 알고리즘 | - |
| `doc` | `document` | 문서 | 예외 | 관용어 — 알고리즘은 `dcm` |
| `emb` | `embedding` | 임베딩 | 알고리즘 | - |
| `end` | `end` | 종료 | 알고리즘 | - |
| `ended` | `ended` | 종료됨 | 예외 | 알고리즘 결과가 `end`(원형)와 충돌. `ended_at` 대칭, 기존 `created`/`updated`/`deleted`/`locked`/`archived`와 동일 패턴 |
| `err` | `error` | 오류 | 알고리즘 | - |
| `file` | `file` | 파일 | 알고리즘 | - |
| `fgpt` | `fingerprint` | - | 예외 | 관용어 — 알고리즘 결과 `fng`가 의미 불명확, 4자로 늘려 가독성 확보 |
| `idm` | `idempotency` | 멱등성 | 알고리즘 | - |
| `id` | `identifier` | - | 예외 | 관용어(팀 합의) |
| `inactive` | `inactive` | 비활성 | 예외 | 관용 유지 — 알고리즘 결과 `inc`는 increment·include와 혼동. `inactive_at` 대칭, 기존 `created`/`updated`/`deleted`/`locked`/`archived`와 동일 패턴 |
| `indexed` | `indexed` | 색인됨 | 예외 | 관용 — `indexed_at` 대칭 |
| `idx` | `indexing` | 색인 실행 | 예외 | 관용어 — 알고리즘은 `ind`로 `indexed`와 충돌 |
| `inv` | `invitation` | 초대 | 알고리즘 | - |
| `json` | `json` | JSON | 알고리즘 | - |
| `key` | `key` | 키 | 알고리즘 | - |
| `keycloak` | `keycloak` | - | 예외 | 고유명사 — 축약하지 않음 |
| `kind` | `kind` | 종류 | 알고리즘 | - |
| `last` | `last` | 마지막 | 알고리즘 | - |
| `loc` | `location` | 위치 | 예외 | 관용어 — 알고리즘은 `lct` |
| `locked` | `locked` | 잠김 | 예외 | 관용 유지 — `locked_at` 대칭, 기존 `created`/`updated`/`deleted`와 동일 패턴 |
| `log` | `log` | 로그 | 알고리즘 | - |
| `mbr` | `member` | 회원 | 예외 | 관용어 — 알고리즘은 `mmb` |
| `msg` | `message` | 메시지 | 예외 | 관용어 — 알고리즘은 `mss` |
| `mdl` | `model` | 모델 | 알고리즘 | - |
| `name` | `name` | 이름 | 알고리즘 | - |
| `next` | `next` | 다음 | 알고리즘 | - |
| `org` | `original` | 원본 | 알고리즘 | - |
| `otc` | `outcome` | 결과 | 알고리즘 | - |
| `own` | `owner` | 소유자 | 알고리즘 | - |
| `pw` | `password` | - | 예외 | 관용어(팀 합의) |
| `path` | `path` | 경로 | 알고리즘 | - |
| `pyl` | `payload` | 적재 데이터 | 알고리즘 | - |
| `pnd` | `pending` | 대기 중 | 알고리즘 | - |
| `qst` | `question` | 질문 | 알고리즘 | - |
| `rsn` | `reason` | 사유 | 알고리즘 | - |
| `rpl` | `reply` | 답글 | 알고리즘 | - |
| `req` | `requester` | 요청자 | 예외 | 관용어 — 알고리즘은 `rqs` |
| `risk` | `risk` | 위험 | 알고리즘 | - |
| `role` | `role` | 역할 | 알고리즘 | - |
| `run` | `run` | 실행 | 알고리즘 | - |
| `seq` | `sequence` | - | 예외 | 관용어 — 알고리즘 결과 `sqn`이 불명확, 업계 통용 축약 |
| `sess` | `session` | 세션 | 예외 | 알고리즘 결과 `sss`가 불량(동일 문자 반복) |
| `src` | `source` | 근거 | 알고리즘 | - |
| `status` | `status` | - | 예외 | 관용 유지 — 알고리즘 결과 `stt`가 부자연스러움(`content`·`title`과 같은 패턴) |
| `subj` | `subject` | 주체 식별자 | 예외 | 관용어 — 알고리즘은 `sbj` |
| `tag` | `tag` | 태그 | 알고리즘 | - |
| `text` | `text` | 텍스트 | 알고리즘 | - |
| `thr` | `thread` | 대화 스레드 | 알고리즘 | - |
| `title` | `title` | 제목 | 예외 | 알고리즘 `ttl`은 Time-To-Live로 통용돼 혼동 |
| `trc` | `trace` | 추적 | 알고리즘 | - |
| `updated` | `updated` | 수정됨 | 예외 | 관용 — `updated_at`은 범용 관례라 원형 유지 |
| `uploaded` | `uploaded` | 업로드됨 | 예외 | 관용 — `uploaded_by` 대칭 |
| `user` | `user` | 사용자 | 알고리즘 | - |
| `ver` | `version` | 버전 | 예외 | 관용어 — 알고리즘은 `vrs` |
