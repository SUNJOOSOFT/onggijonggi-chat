# worker — Python 문서 워커

06 DOCUMENT 계층. 격리 컨테이너에서 문서 업로드·분석·구조화 편집.
- python-docx · openpyxl · python-pptx, LibreOffice headless(PDF 변환)
- 리소스·네트워크 제한, 화이트리스트 연산만(임의 코드 실행 없음)

`doc/`의 FastAPI가 문서 생성·템플릿 처리와 Thread 문서 원본 저장을 제공한다. 브라우저는 BFF만 호출하며,
워커는 내부 API key와 Tenant 식별자를 확인한다. 사용자·방·문서 인가는 BFF의 책임이다.

## Thread 문서 원본

- 내부 경로: `/api/v1/thread-sources/{threadId}/{documentId}/{sha256}`.
- PUT은 multipart `file`을 받아 변환·잘라내기 없이 저장한다. GET은 원본 바이트, DELETE는 멱등 삭제다.
- 모든 요청에 `X-Internal-Api-Key`, UUID `X-Tenant-Id`가 필요하다. key와 `Content-Length` 상한은 본문을 파싱하기 전에
  확인한다 — 파일 파트는 크기 제한 없이 임시 파일로 스풀되므로, key 없는 요청이 메모리 tmpfs를 채우지 못하게 한다. PUT은 BFF 예약 만료 시각(Unix 초)의
  `X-Source-Expires-At`도 필요하며, 만료됐거나 125초보다 먼 요청은 거부한다.
- 원본 경로는 저장소 루트의 `thread-sources/` 아래에서 Tenant·Thread·문서·SHA-256으로 분리한다. 문서 생성 산출물의
  `SEAWEED_ROOT_PREFIX`는 따르지 않는다. 사용자 파일명을 저장소 경로에 넣지 않는다.
- 새 원본은 UUID `X-Source-Attempt-Id`로 저장 시도를 추가 격리한다. GET·DELETE도 등록/정리 행의 같은 시도를
  전달한다. 헤더 없는 기존 원본은 기존 경로를 유지한다. 실패 등록은 이전 정리 완료 뒤 새 시도로 재시도한다.
- `MAX_SOURCE_BYTES` 기본값은 10MiB다. BFF도 같은 상한을 코드 상수로 둔다 — 워커 값만 낮추면 BFF가 받은 파일을
  워커가 거절해 저장소 장애(503)로 보이므로 둘을 함께 바꾼다. 루트 compose는 이 값을 넘기지 않는다. 원본 통신의 inactivity timeout은 최대 30초이며,
  배포 서버·DB·워커의 시계를 동기화해야 예약 만료와 180초 정리 유예가 일치한다.
- 저장 전후 예약 만료를 확인하고 만료된 저장은 삭제 후 거부한다. 현재 단일 워커 프로세스는 같은 원본 키의
  PUT·DELETE를 직렬화한다. 전체 처리 시간이 30초로 제한되거나 다중 워커·프로세스 중단에도 외부 저장소가
  완전히 fencing된다는 보장은 아니다. 다중 워커 배포는 저장소 측 fencing/고아 원본 대조를 별도로 갖춰야 한다.
- 원본 저장 성공은 ETL/검색 완료가 아니다. 추출·청킹·임베딩·색인은 별도 후속 작업이다.
- DB 변경 이력·정리 재시도는 BFF가 보관한다. 워커의 DELETE는 이미 없는 원본에도 성공한다.

Compose의 BFF는 `APP_DOCUMENT_WORKER_URL`과 `APP_DOCUMENT_INTERNAL_API_KEY`를 사용한다.
후자는 워커의 `DOCUMENT_WORKER_INTERNAL_API_KEY`와 같은 값이어야 한다. 호스트 BFF에서 실행할 때는
실제로 접근 가능한 워커 주소와 같은 내부 key를 명시한다. 저장소·워커를 브라우저에 직접 공개하지 않는다.
