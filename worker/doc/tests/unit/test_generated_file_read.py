"""생성된 문서를 BFF가 읽는 내부 API를 본다 — 허용하는 저장 위치의 모양, 다른 경로·tenant 거절, 오류 변환, 응답 헤더."""

import pytest
from fastapi.testclient import TestClient

from app.api.routes.documents import get_document_service
from app.core.config import Settings
from app.errors import WorkerError
from app.main import app
from app.services.document_service import DocumentService

FILE_ID = "01JZ8R13BFA6QX2GJ1H4M9X8V7"
KEY = f"documents/tenant-a/2026/09/01/{FILE_ID}/report.docx"
HEADERS = {"X-Internal-Api-Key": "test-key", "X-Tenant-Id": "tenant-a"}


class FakeReadStorage:
    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}
        self.reads: list[tuple[str, int]] = []
        self.failure: WorkerError | None = None

    def read(self, object_key: str, max_bytes: int) -> bytes:
        self.reads.append((object_key, max_bytes))
        if self.failure:
            raise self.failure
        if object_key not in self.files:
            raise WorkerError(404, "SOURCE_NOT_FOUND", "Document source is unavailable")
        return self.files[object_key]


def service(storage: FakeReadStorage, **overrides) -> DocumentService:
    return DocumentService(Settings(internal_api_key="test-key", **overrides), storage=storage)


def test_reads_a_document_stored_by_create_for_the_same_tenant() -> None:
    storage = FakeReadStorage()
    storage.files[KEY] = b"docx-bytes"

    assert service(storage).read_output(KEY, "tenant-a") == b"docx-bytes"


def test_passes_the_output_size_limit_to_the_storage() -> None:
    storage = FakeReadStorage()
    storage.files[KEY] = b"x"

    service(storage, max_output_bytes=1234).read_output(KEY, "tenant-a")

    assert storage.reads == [(KEY, 1234)]


@pytest.mark.parametrize(
    "object_key",
    [
        # 다른 tenant의 문서
        f"documents/tenant-b/2026/09/01/{FILE_ID}/report.docx",
        # tenant 이름이 접두사로 겹치는 다른 tenant
        f"documents/tenant-a-evil/2026/09/01/{FILE_ID}/report.docx",
        # 템플릿·방 문서 원본 같은 다른 저장 영역
        f"templates/tenant-a/2026/09/01/{FILE_ID}/report.docx",
        "thread-sources/tenant-a/2026/09/01/x/report.docx",
        # 경로 이탈
        f"documents/tenant-a/../tenant-b/2026/09/01/{FILE_ID}/report.docx",
        f"documents/tenant-a/2026/09/01/{FILE_ID}/../report.docx",
        f"documents/tenant-a/2026/09/01/{FILE_ID}/..",
        # 날짜·파일 id·파일명 자리가 아닌 모양
        "documents/tenant-a/report.docx",
        f"documents/tenant-a/2026/09/01/{FILE_ID}/sub/report.docx",
        f"documents/tenant-a/2026/09/01/{FILE_ID.lower()}/report.docx",
        f"documents/tenant-a/2026/9/1/{FILE_ID}/report.docx",
        f"documents/tenant-a/2026/09/01/{FILE_ID}/",
        f"documents/tenant-a/2026/09/01/{FILE_ID}/a\\b.docx",
        "",
    ],
)
def test_rejects_any_key_that_create_would_not_have_produced(object_key: str) -> None:
    storage = FakeReadStorage()

    with pytest.raises(WorkerError) as error:
        service(storage).read_output(object_key, "tenant-a")

    assert (error.value.status_code, error.value.code) == (400, "VALIDATION_ERROR")
    assert storage.reads == []


def test_a_missing_file_is_reported_as_a_generated_document_not_a_source() -> None:
    with pytest.raises(WorkerError) as error:
        service(FakeReadStorage()).read_output(KEY, "tenant-a")

    assert (error.value.status_code, error.value.code) == (404, "FILE_NOT_FOUND")


def test_storage_failures_other_than_not_found_pass_through() -> None:
    storage = FakeReadStorage()
    storage.failure = WorkerError(503, "STORAGE_UNAVAILABLE", "Document storage is unavailable")

    with pytest.raises(WorkerError) as error:
        service(storage).read_output(KEY, "tenant-a")

    assert (error.value.status_code, error.value.code) == (503, "STORAGE_UNAVAILABLE")


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_KEY", "test-key")
    storage = FakeReadStorage()
    storage.files[KEY] = b"docx-bytes"
    app.dependency_overrides[get_document_service] = lambda: service(storage)
    with TestClient(app) as test_client:
        yield test_client, storage
    app.dependency_overrides.pop(get_document_service, None)


def test_endpoint_returns_the_bytes_as_an_opaque_download(client) -> None:
    test_client, _ = client

    response = test_client.get("/api/v1/documents/files", params={"objectKey": KEY}, headers=HEADERS)

    assert response.status_code == 200
    assert response.content == b"docx-bytes"
    # 열람 이름과 형식은 BFF가 정한다 — 내부 API가 내용을 해석하게 하지 않는다.
    assert response.headers["content-type"] == "application/octet-stream"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["cache-control"] == "no-store"


def test_endpoint_requires_the_internal_api_key(client) -> None:
    test_client, storage = client

    response = test_client.get(
        "/api/v1/documents/files",
        params={"objectKey": KEY},
        headers={**HEADERS, "X-Internal-Api-Key": "wrong"},
    )

    assert response.status_code == 401
    assert storage.reads == []


def test_endpoint_does_not_serve_another_tenants_document(client) -> None:
    test_client, storage = client

    response = test_client.get(
        "/api/v1/documents/files",
        params={"objectKey": KEY},
        headers={**HEADERS, "X-Tenant-Id": "tenant-b"},
    )

    assert response.status_code == 400
    assert storage.reads == []


def test_endpoint_reports_an_unknown_file_with_the_standard_error_body(client) -> None:
    test_client, _ = client
    other = f"documents/tenant-a/2026/09/01/{FILE_ID[:-1]}9/report.docx"

    response = test_client.get(
        "/api/v1/documents/files",
        params={"objectKey": other},
        headers={**HEADERS, "X-Request-Id": "request-9"},
    )

    assert response.status_code == 404
    assert response.json() == {
        "code": "FILE_NOT_FOUND",
        "message": "Generated document is unavailable",
        "requestId": "request-9",
    }


def test_endpoint_requires_an_object_key(client) -> None:
    test_client, _ = client

    response = test_client.get("/api/v1/documents/files", headers=HEADERS)

    assert response.status_code == 400
