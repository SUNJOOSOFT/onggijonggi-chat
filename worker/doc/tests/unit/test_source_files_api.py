"""원본 저장은 ETL·방 인가와 분리하고 내부 인증·식별·크기·재시도 경계를 검증한다."""

from hashlib import sha256
from time import time
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from starlette.requests import Request

from app.api.routes.source_files import get_source_storage, source_key
from app.errors import WorkerError
from app.main import app


class FakeSourceStorage:
    def __init__(self):
        self.files = {}
        self.calls = []
        self.failure = None

    def check(self):
        if self.failure:
            raise self.failure

    def upload_bytes(self, key, content):
        self.check()
        self.calls.append(("save", key))
        self.files[key] = content

    def read(self, key, max_bytes):
        self.check()
        self.calls.append(("read", key))
        if key not in self.files:
            raise WorkerError(404, "SOURCE_NOT_FOUND", "Source not found")
        return self.files[key]

    def delete(self, key):
        self.check()
        self.calls.append(("delete", key))
        self.files.pop(key, None)


@pytest.fixture
def sources(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_KEY", "test-key")
    monkeypatch.setenv("MAX_SOURCE_BYTES", "16")
    storage = FakeSourceStorage()
    app.dependency_overrides[get_source_storage] = lambda: storage
    with TestClient(app) as client:
        yield client, storage
    app.dependency_overrides.pop(get_source_storage, None)


def location(content=b"original"):
    tenant, thread, document = uuid4(), uuid4(), uuid4()
    digest = sha256(content).hexdigest()
    url = f"/api/v1/thread-sources/{thread}/{document}/{digest}"
    headers = {"X-Internal-Api-Key": "test-key", "X-Tenant-Id": str(tenant), "X-Source-Expires-At": str(int(time()) + 120)}
    return url, headers, source_key(tenant, thread, document, digest)


def test_source_round_trip_and_idempotent_delete(sources):
    client, storage = sources
    url, headers, key = location()
    for _ in range(2):
        response = client.put(url, headers=headers, files={"file": ("../../sample.txt", b"original")})
        assert response.status_code == 201
        assert response.json()["size"] == 8
        assert "READY" not in response.text
    assert list(storage.files) == [key]
    response = client.get(url, headers=headers)
    assert response.content == b"original"
    assert response.headers["content-type"] == "application/octet-stream"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-content-type-options"] == "nosniff"
    for _ in range(2):
        assert client.delete(url, headers=headers).status_code == 204
    assert client.get(url, headers=headers).status_code == 404


@pytest.mark.parametrize("key", ["incorrect", "키".encode()])
@pytest.mark.parametrize("method", ["get", "put", "delete"])
def test_internal_key_required_before_storage_access(sources, method, key):
    client, storage = sources
    url, headers, _ = location()
    headers["X-Internal-Api-Key"] = key
    kwargs = {"files": {"file": ("source.txt", b"original")}} if method == "put" else {}
    assert getattr(client, method)(url, headers=headers, **kwargs).status_code == 401
    assert storage.calls == []


@pytest.fixture
def unparsed(monkeypatch):
    """본문 파싱(파일 파트 스풀)에 닿으면 실패시킨다. 미들웨어가 그 전에 끊는지 본다."""

    async def form(self, *args, **kwargs):
        raise AssertionError("multipart body was parsed")

    monkeypatch.setattr(Request, "form", form)


@pytest.mark.parametrize("key, size, status, code", [
    ("incorrect", 8, 401, "UNAUTHORIZED"),
    ("test-key", 70 * 1024, 413, "FILE_TOO_LARGE"),
])
def test_key_and_declared_size_are_checked_before_parsing(sources, unparsed, key, size, status, code):
    body = b"x" * size
    client, storage = sources
    url, headers, _ = location()
    headers["X-Internal-Api-Key"] = key
    response = client.put(url, headers=headers, files={"file": ("source.txt", body)})
    assert response.status_code == status
    assert response.json()["code"] == code
    assert storage.calls == []


@pytest.mark.parametrize("content, status, code", [
    (b"", 400, "EMPTY_SOURCE"),
    (b"x" * 17, 413, "FILE_TOO_LARGE"),
    (b"changed", 400, "SOURCE_DIGEST_MISMATCH"),
])
def test_invalid_upload_does_not_write(sources, content, status, code):
    client, storage = sources
    url, headers, _ = location()
    response = client.put(url, headers=headers, files={"file": ("source.txt", content)})
    assert response.status_code == status
    assert response.json()["code"] == code
    assert storage.calls == []


def test_invalid_identifier_cannot_choose_arbitrary_path(sources):
    client, storage = sources
    url, headers, _ = location()
    response = client.get(url.replace(url.split("/")[4], "not-a-uuid"), headers=headers)
    assert response.status_code == 400
    headers["X-Tenant-Id"] = "../other-tenant"
    assert client.get(url, headers=headers).status_code == 400
    assert storage.calls == []


def test_source_isolation_by_tenant_and_thread(sources):
    client, _ = sources
    url, headers, _ = location()
    assert client.put(url, headers=headers, files={"file": ("source.txt", b"original")}).status_code == 201
    other_headers = {**headers, "X-Tenant-Id": str(uuid4())}
    assert client.get(url, headers=other_headers).status_code == 404
    other_url = url.replace(url.split("/")[4], str(uuid4()))
    assert client.get(other_url, headers=headers).status_code == 404
    assert client.get(url, headers=headers).status_code == 200


def test_different_content_never_overwrites_original(sources):
    client, storage = sources
    url, headers, key = location()
    assert client.put(url, headers=headers, files={"file": ("source.txt", b"original")}).status_code == 201
    assert client.put(url, headers=headers, files={"file": ("source.txt", b"modified")}).status_code == 400
    assert storage.files[key] == b"original"


def test_expiry_during_storage_removes_original_instead_of_succeeding(sources, monkeypatch):
    client, storage = sources
    url, headers, key = location()
    now = int(time())
    monkeypatch.setattr("app.api.routes.source_files.time", lambda: now)
    original_upload = storage.upload_bytes

    def slow_upload(path, content):
        original_upload(path, content)
        monkeypatch.setattr("app.api.routes.source_files.time", lambda: now + 121)

    monkeypatch.setattr(storage, "upload_bytes", slow_upload)
    response = client.put(url, headers=headers, files={"file": ("source.txt", b"original")})
    assert response.status_code == 409
    assert key not in storage.files


def test_old_attempt_delete_never_removes_new_attempt_source(sources):
    client, storage = sources
    url, headers, legacy_key = location()
    old_attempt, new_attempt = uuid4(), uuid4()
    old_headers = {**headers, "X-Source-Attempt-Id": str(old_attempt)}
    new_headers = {**headers, "X-Source-Attempt-Id": str(new_attempt)}
    for request_headers in (old_headers, new_headers):
        assert client.put(url, headers=request_headers, files={"file": ("source.txt", b"original")}).status_code == 201
    assert client.delete(url, headers=old_headers).status_code == 204
    assert client.get(url, headers=old_headers).status_code == 404
    assert client.get(url, headers=new_headers).content == b"original"
    assert list(storage.files) == [f"{legacy_key}/{new_attempt}"]


def test_delete_waits_for_inflight_upload_before_removing_original(sources, monkeypatch):
    client, storage = sources
    url, headers, key = location()
    entered, release, delete_waiting = Event(), Event(), Event()
    original_upload = storage.upload_bytes

    def blocked_upload(path, content):
        entered.set()
        assert release.wait(5)
        original_upload(path, content)

    from app.api.routes import source_files
    original_lock = source_files.source_lock

    class ObservedLock:
        def __enter__(self):
            delete_waiting.set()
            original_lock(key).acquire()

        def __exit__(self, *args):
            original_lock(key).release()

    monkeypatch.setattr(storage, "upload_bytes", blocked_upload)
    with ThreadPoolExecutor(max_workers=2) as pool:
        put = pool.submit(client.put, url, headers=headers, files={"file": ("source.txt", b"original")})
        assert entered.wait(5)
        monkeypatch.setattr(source_files, "source_lock", lambda path: ObservedLock())
        delete = pool.submit(client.delete, url, headers=headers)
        try:
            assert delete_waiting.wait(5)
            assert not delete.done()
        finally:
            release.set()
        assert put.result(timeout=5).status_code == 201
        assert delete.result(timeout=5).status_code == 204
    assert key not in storage.files


def test_corrupted_source_does_not_return_content(sources):
    client, storage = sources
    url, headers, key = location()
    storage.files[key] = b"corrupted"
    response = client.get(url, headers=headers)
    assert response.status_code == 503
    assert response.json()["code"] == "SOURCE_INTEGRITY_ERROR"


@pytest.mark.parametrize("offset,status", [(-1, 409), (1000, 400)])
def test_expired_or_unbounded_upload_cannot_write_after_cleanup(sources, offset, status):
    client, storage = sources
    url, headers, _ = location()
    headers["X-Source-Expires-At"] = str(int(time()) + offset)
    assert client.put(url, headers=headers, files={"file": ("source.txt", b"original")}).status_code == status
    assert storage.calls == []


@pytest.mark.parametrize("method", ["get", "put", "delete"])
def test_storage_failure_is_not_reported_as_success(sources, method):
    client, storage = sources
    storage.failure = WorkerError(503, "STORAGE_UNAVAILABLE", "Storage failed")
    url, headers, _ = location()
    kwargs = {"files": {"file": ("source.txt", b"original")}} if method == "put" else {}
    response = getattr(client, method)(url, headers=headers, **kwargs)
    assert response.status_code == 503
    assert response.json()["code"] == "STORAGE_UNAVAILABLE"
