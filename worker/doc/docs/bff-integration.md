# BFF 연동 가이드

이 문서는 Spring AI BFF가 현재 `document-worker`를 내부 HTTP로 호출하는 방법을 정리한다. FrontEnd는 Worker나 SeaweedFS를 직접 호출하지 않고 BFF만 호출한다.

현재 Worker는 다음 세 기능을 제공한다.

1. 검증된 DOCX renderer DSL로 DOCX, PDF 또는 Markdown을 생성하고 SeaweedFS에 저장한다.
2. 템플릿 원본 파일을 검증해 SeaweedFS에 저장한다.
3. 대화방(Thread) 문서의 원본을 변환 없이 저장·조회·삭제한다(`/api/v1/thread-sources`). 경로·헤더 계약은 [worker README](../../README.md)「Thread 문서 원본」에 있다. 이 경로의 `X-Tenant-Id`는 UUID다.

템플릿을 생성 문서에 적용하는 기능, `TemplateManifest`, 템플릿 선택은 아직 구현하지 않았다.

## BFF 설정

BFF는 `onggijonggi-chat/infra/docker-compose.yml`의 `app-net`에서 Worker **서비스 이름**으로 호출한다. 컨테이너명(`worker-doc`)이 아니라 Compose 서비스명(`document-worker`)을 사용한다.

```yaml
APP_DOCUMENT_WORKER_URL: http://document-worker:8100
APP_DOCUMENT_INTERNAL_API_KEY: ${DOCUMENT_WORKER_INTERNAL_API_KEY}
```

API key는 BFF와 Worker에 같은 secret으로 주입한다. 브라우저나 API 응답에 노출하지 않는다.

메인 Compose에 병합할 컨테이너명은 아래와 같다.

| Compose 서비스 | 컨테이너명 |
| --- | --- |
| `document-worker` | `worker-doc` |
| `seaweedfs-master` | `infra-seaweed-master` |
| `seaweedfs-volume` | `infra-seaweed-volume` |
| `seaweedfs-filer` | `infra-seaweed-filer` |

BFF가 생성 결과를 SeaweedFS Filer에서 직접 읽을 예정이면, 현재 `app-net`에 연결된 `bff` 서비스에 `document-worker-net`도 추가한다. Filer의 서비스 DNS는 `seaweedfs-filer:8888`이며, BFF는 tenant 권한을 확인한 뒤에만 내부 `objectKey`로 읽는다.

```yaml
  bff:
    networks:
      - app-net
      - document-worker-net
```

## 문서 생성

### BFF 처리 책임

1. FrontEnd 요청의 인증·인가와 tenant 확인을 수행한다.
2. LLM 또는 업무 로직의 결과를 공통 `DocumentPlan`으로 검증한 뒤, 현재 DOCX renderer DSL로 컴파일한다.
3. BFF가 확정한 `X-Tenant-Id`, 추적용 `X-Request-Id`, 내부 API key를 붙여 Worker에 요청한다.
4. Worker 응답의 `fileId`, `objectKey` 등 메타데이터를 BFF 데이터 저장소에 보관한다.
5. 다운로드는 이후 BFF의 별도 권한 확인 API가 처리한다. `objectKey`나 SeaweedFS URL을 브라우저에 직접 전달하지 않는다.

### 출력 파일 형식 결정

출력 파일 형식은 BFF가 Worker 요청 DSL의 `outputFormat`과 `fileName`으로 결정한다. FrontEnd가 DOCX/PDF/Markdown 선택 UI를 제공할 수는 있지만, 그 값은 BFF가 허용값으로 검증한 뒤 Worker 요청으로 변환한다. FrontEnd는 Worker를 직접 호출하지 않는다.

| BFF가 전송하는 값 | 현재 허용값 | 의미 |
| --- | --- | --- |
| `documentType` | `DOCX` | Worker의 원본 renderer 유형. 현재 DOCX renderer만 구현되어 있다. |
| `outputFormat` | `DOCX`, `PDF`, `MD` | 최종 저장 파일 형식. `PDF`이면 Worker가 DOCX 생성 후 PDF로 변환하고, `MD`는 같은 DSL을 Markdown으로 직접 렌더링한다. |
| `fileName` | output format과 같은 확장자 | `DOCX`는 `.docx`, `PDF`는 `.pdf`, `MD`는 `.md`를 사용한다. |

Worker는 임의 파일 타입을 받지 않는다. XLSX/PPTX 생성은 renderer가 구현된 후 별도 DSL과 함께 허용한다.

### HTTP 요청

```http
POST /api/v1/documents HTTP/1.1
Host: document-worker:8100
Content-Type: application/json
X-Internal-Api-Key: <worker-api-key>
X-Tenant-Id: tenant-a
X-Request-Id: 01J...
```

`X-Internal-Api-Key`와 `X-Tenant-Id`는 필수다. tenant ID는 `[A-Za-z0-9_-]`만 사용하며 최대 100자다. `X-Request-Id`를 생략하면 Worker가 생성해 응답에 반환한다.

### DOCX renderer DSL

현재 `documentType`은 `DOCX`만 지원한다. `outputFormat`은 `DOCX`, `PDF`, `MD`다. PDF를 요청하면 Worker가 같은 DSL로 DOCX를 생성한 뒤 PDF로 변환하고, MD를 요청하면 같은 DSL을 Markdown으로 직접 렌더링한다.

```json
{
  "documentType": "DOCX",
  "outputFormat": "DOCX",
  "fileName": "weekly-report.docx",
  "title": "2026년 9월 1주차 주간 보고",
  "templateData": {},
  "operations": [
    {"type": "heading", "level": 1, "text": "핵심 현황"},
    {"type": "paragraph", "text": "문서 생성 기능을 배포했습니다."},
    {"type": "bullet_list", "items": ["API 구현", "통합 테스트"]},
    {
      "type": "table",
      "columns": ["항목", "상태"],
      "rows": [["Document Worker", "완료"], ["SeaweedFS", "완료"]]
    },
    {"type": "page_break"}
  ],
  "metadata": {"source": "bff"}
}
```

지원 operation은 `heading`, `paragraph`, `bullet_list`, `numbered_list`, `table`, `page_break`다. 정의되지 않은 필드는 거절된다. `templateData`는 필수 객체이며 현재는 렌더링에 사용하지 않으므로 BFF는 `{}`를 전송한다.

PDF 요청은 `outputFormat`과 `fileName` 확장자를 함께 변경한다.

```json
{
  "documentType": "DOCX",
  "outputFormat": "PDF",
  "fileName": "weekly-report.pdf",
  "title": "2026년 9월 1주차 주간 보고",
  "templateData": {},
  "operations": [{"type": "paragraph", "text": "PDF로 생성할 내용"}],
  "metadata": {"source": "bff"}
}
```

Markdown 요청도 같은 operation DSL을 사용한다. `page_break`는 Markdown의 `<!-- page-break -->` 주석으로 표현된다.

```json
{
  "documentType": "DOCX",
  "outputFormat": "MD",
  "fileName": "weekly-report.md",
  "title": "2026년 9월 1주차 주간 보고",
  "templateData": {},
  "operations": [{"type": "paragraph", "text": "Markdown으로 생성할 내용"}],
  "metadata": {"source": "bff"}
}
```

### Docker 환경 검증 예시

로컬 Compose 환경에서는 아래 요청으로 MD 생성과 SeaweedFS 저장을 확인할 수 있다.

```bash
jq '.outputFormat = "MD" | .fileName = "meeting-minutes.md"' \
  tests/fixtures/meeting-minutes.json \
| curl --fail --request POST http://127.0.0.1:8100/api/v1/documents \
  --header 'Content-Type: application/json' \
  --header 'X-Internal-Api-Key: local-test-key' \
  --header 'X-Tenant-Id: tenant-a' \
  --header 'X-Request-Id: markdown-e2e-001' \
  --data-binary @-
```

성공 응답의 `contentType`은 `text/markdown`이고 `objectKey`는 `documents/{tenantId}/.../{fileName}` 형식이다. BFF는 이 object key를 내부 메타데이터로만 보관한다.

### Spring WebClient 예시

```java
webClient.post()
    .uri(documentWorkerBaseUrl + "/api/v1/documents")
    .header("X-Internal-Api-Key", documentWorkerApiKey)
    .header("X-Tenant-Id", tenantId)
    .header("X-Request-Id", requestId)
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(documentRequest)
    .retrieve()
    .bodyToMono(DocumentResponse.class);
```

성공하면 `201 Created`와 다음 메타데이터를 반환한다.

```json
{
  "fileId": "01M...",
  "fileName": "weekly-report.docx",
  "objectKey": "documents/tenant-a/2026/09/01/01M.../weekly-report.docx",
  "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "size": 128392,
  "createdAt": "2026-09-01T10:12:34Z",
  "requestId": "01J..."
}
```

## 템플릿 원본 저장

템플릿 저장은 생성과 별도 endpoint다. BFF는 사용자가 업로드한 파일을 검증·인가한 뒤 파일 스트림을 이 endpoint로 전달한다.

```http
POST /api/v1/documents/templates HTTP/1.1
Host: document-worker:8100
Content-Type: multipart/form-data
X-Internal-Api-Key: <worker-api-key>
X-Tenant-Id: tenant-a
X-Request-Id: 01J...

template=<uploaded file>
```

multipart field 이름은 반드시 `template`다. 지원 형식은 DOCX, XLSX, PPTX, PDF이며 확장자·MIME type·기본 문서 구조를 검증한다. Worker는 파일을 다음 형태의 내부 object key로 저장한다.

```text
templates/{tenantId}/{YYYY}/{MM}/{DD}/{templateId}/{fileName}
```

성공하면 `201 Created`와 `templateId`, `fileName`, `objectKey`, `contentType`, `size`, `createdAt`, `requestId`를 반환한다. BFF는 이 값을 템플릿 레지스트리에 보관할 수 있지만, 현재 생성 endpoint에는 `templateId`나 `objectKey`를 보내지 않는다.

### Spring WebClient 예시

```java
MultipartBodyBuilder body = new MultipartBodyBuilder();
body.asyncPart("template", filePart.content(), DataBuffer.class)
    .filename(filePart.filename())
    .contentType(filePart.headers().getContentType());

webClient.post()
    .uri(documentWorkerBaseUrl + "/api/v1/documents/templates")
    .header("X-Internal-Api-Key", documentWorkerApiKey)
    .header("X-Tenant-Id", tenantId)
    .header("X-Request-Id", requestId)
    .contentType(MediaType.MULTIPART_FORM_DATA)
    .body(BodyInserters.fromMultipartData(body.build()))
    .retrieve()
    .bodyToMono(TemplateResponse.class);
```

## 오류 처리

Worker 오류 응답은 항상 다음 구조다.

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Document request is invalid",
  "requestId": "01J..."
}
```

주요 상태는 `400`(DSL·tenant·파일명 오류), `401`(내부 API key 오류), `413`(크기 초과), `415`(지원하지 않는 템플릿 형식), `422`(손상된 템플릿), `500`(렌더/변환 실패), `503`(SeaweedFS 오류), `504`(시간 초과)다. BFF는 `requestId`를 로그와 사용자 오류 추적에 사용하고, Worker 내부 URL이나 상세 예외는 외부 응답에 포함하지 않는다.
