"""원본 저장소의 응답 상한과 HTTP 실패·멱등 삭제를 실제 클라이언트 경계에서 검증한다."""

from contextlib import contextmanager

import httpx
import pytest

from app.errors import WorkerError
from app.storage.seaweedfs import SeaweedFsStorage


def response(status=200, content=b""):
    return httpx.Response(status, content=content, request=httpx.Request("GET", "http://filer/source"))


@contextmanager
def streamed(status=200, content=b""):
    yield response(status, content)


def test_read_is_bounded_and_preserves_bytes(monkeypatch):
    monkeypatch.setattr(httpx, "stream", lambda *args, **kwargs: streamed(content=b"source"))
    storage = SeaweedFsStorage("http://filer", 5)
    assert storage.read("thread-sources/key", 6) == b"source"
    with pytest.raises(WorkerError) as error:
        storage.read("thread-sources/key", 5)
    assert error.value.status_code == 503


def test_missing_source_is_not_storage_outage(monkeypatch):
    monkeypatch.setattr(httpx, "stream", lambda *args, **kwargs: streamed(404))
    with pytest.raises(WorkerError) as error:
        SeaweedFsStorage("http://filer", 5).read("thread-sources/key", 16)
    assert error.value.status_code == 404


@pytest.mark.parametrize("status", [200, 204, 404])
def test_delete_accepts_success_and_already_missing(monkeypatch, status):
    monkeypatch.setattr(httpx, "delete", lambda *args, **kwargs: response(status))
    SeaweedFsStorage("http://filer", 5).delete("thread-sources/key")


@pytest.mark.parametrize("operation", ["upload", "read", "delete"])
def test_http_error_propagates_as_retryable_failure(monkeypatch, operation):
    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: response(500))
    monkeypatch.setattr(httpx, "stream", lambda *args, **kwargs: streamed(500))
    monkeypatch.setattr(httpx, "delete", lambda *args, **kwargs: response(500))
    storage = SeaweedFsStorage("http://filer", 5)
    with pytest.raises(WorkerError) as error:
        if operation == "upload":
            storage.upload_bytes("thread-sources/key", b"source")
        elif operation == "read":
            storage.read("thread-sources/key", 16)
        else:
            storage.delete("thread-sources/key")
    assert error.value.status_code == 503


def test_upload_never_uses_client_filename_as_storage_path(monkeypatch):
    calls = []

    def post(url, **kwargs):
        calls.append((url, kwargs))
        return response(201)

    monkeypatch.setattr(httpx, "post", post)
    SeaweedFsStorage("http://filer/", 5).upload_bytes("thread-sources/key", b"original")
    assert calls[0][0] == "http://filer/thread-sources/key"
    assert calls[0][1]["files"] == {"file": ("source", b"original", "application/octet-stream")}
