"""Thread RAG 원본의 내부 저장 경계. 사용자·방 인가는 호출하는 BFF의 책임이다.

UUID와 원본 SHA-256으로 경로를 구성한다. 임의 경로·파일명·공개 저장소 URL을 받지 않으며,
같은 문서의 다른 내용도 기존 원본을 덮어쓰지 않는다. 원본 저장은 ETL 완료를 뜻하지 않는다.
"""

from hashlib import sha256
from threading import Lock
from time import time
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, File, Header, Path, Response, UploadFile

from app.api.routes.documents import authorize, get_settings
from app.core.config import Settings
from app.errors import WorkerError
from app.storage.seaweedfs import SeaweedFsStorage


SOURCE_PATH_PREFIX = "/api/v1/thread-sources/"
router = APIRouter(prefix=SOURCE_PATH_PREFIX.rstrip("/"), tags=["thread-sources"])
Digest = Annotated[str, Path(pattern=r"^[a-f0-9]{64}$")]
SettingsDependency = Annotated[Settings, Depends(get_settings)]
ApiKey = Annotated[str, Header(alias="X-Internal-Api-Key")]
TenantId = Annotated[UUID, Header(alias="X-Tenant-Id")]

# 단일 워커에서 같은 원본의 DELETE가 진행 중 PUT을 앞지르지 않는다.
# 고정된 수의 잠금을 사용해 문서 수에 따라 메모리가 증가하지 않게 한다. 다른 원본이 같은 칸에 걸리면 느린 저장을
# 기다리다 예약이 만료될 수 있어, 칸 수를 넉넉히 둔다(두 원본이 겹칠 확률 1/1024).
_source_locks = tuple(Lock() for _ in range(1024))


def source_lock(key: str):
    return _source_locks[int.from_bytes(sha256(key.encode()).digest()[:2], "big") % len(_source_locks)]


def get_source_storage(settings: SettingsDependency) -> SeaweedFsStorage:
    return SeaweedFsStorage(settings.seaweed_filer_url, min(settings.render_timeout_seconds, 30))


StorageDependency = Annotated[SeaweedFsStorage, Depends(get_source_storage)]


def source_key(tenant_id: UUID, thread_id: UUID, document_id: UUID, digest: str, attempt_id: UUID | None = None) -> str:
    # 파일명이 같아도 별도 문서다. 내용 digest까지 경로에 넣어 동일 요청 재시도만 같은 원본을 가리킨다.
    key = f"thread-sources/{tenant_id}/{thread_id}/{document_id}/{digest}"
    return key if attempt_id is None else f"{key}/{attempt_id}"


@router.put("/{thread_id}/{document_id}/{digest}", status_code=201)
def save_source(
    thread_id: UUID,
    document_id: UUID,
    digest: Digest,
    settings: SettingsDependency,
    storage: StorageDependency,
    internal_api_key: ApiKey,
    tenant_id: TenantId,
    expires_at: Annotated[int, Header(alias="X-Source-Expires-At")],
    file: Annotated[UploadFile, File()],
    attempt_id: Annotated[UUID | None, Header(alias="X-Source-Attempt-Id")] = None,
) -> dict[str, str | int]:
    # sync 경로이므로 파일 읽기와 저장소 통신은 FastAPI의 thread pool에서 실행된다.
    authorize(settings, internal_api_key, str(tenant_id))
    content = file.file.read(settings.max_source_bytes + 1)
    if not content:
        raise WorkerError(400, "EMPTY_SOURCE", "Document source must not be empty")
    if len(content) > settings.max_source_bytes:
        raise WorkerError(413, "FILE_TOO_LARGE", "Document source exceeds upload limit")
    if sha256(content).hexdigest() != digest:
        raise WorkerError(400, "SOURCE_DIGEST_MISMATCH", "Document source checksum does not match")
    key = source_key(tenant_id, thread_id, document_id, digest, attempt_id)
    with source_lock(key):
        # 잠금 대기와 실제 저장 후에도 검사한다. HTTPX timeout은 전체 처리 기한이 아니다.
        if expires_at <= time():
            raise WorkerError(409, "SOURCE_UPLOAD_EXPIRED", "Source upload reservation expired")
        # BFF 예약은 120초다(ThreadDocumentService.RESERVATION_SECONDS). 시계 차이 5초까지만 허용한다.
        if expires_at > time() + 125:
            raise WorkerError(400, "INVALID_SOURCE_EXPIRY", "Source upload expiry exceeds reservation window")
        storage.upload_bytes(key, content)
        if expires_at <= time():
            storage.delete(key)
            raise WorkerError(409, "SOURCE_UPLOAD_EXPIRED", "Source upload reservation expired")
    return {"documentId": str(document_id), "sha256": digest, "size": len(content)}


@router.get("/{thread_id}/{document_id}/{digest}")
def read_source(
    thread_id: UUID,
    document_id: UUID,
    digest: Digest,
    settings: SettingsDependency,
    storage: StorageDependency,
    internal_api_key: ApiKey,
    tenant_id: TenantId,
    attempt_id: Annotated[UUID | None, Header(alias="X-Source-Attempt-Id")] = None,
) -> Response:
    authorize(settings, internal_api_key, str(tenant_id))
    content = storage.read(source_key(tenant_id, thread_id, document_id, digest, attempt_id), settings.max_source_bytes)
    if sha256(content).hexdigest() != digest:
        raise WorkerError(503, "SOURCE_INTEGRITY_ERROR", "Document source integrity check failed")
    # 브라우저 열람 이름·방 권한은 BFF가 정한다. 내부 API가 HTML 같은 업로드 내용을 실행하게 하지 않는다.
    return Response(
        content=content,
        media_type="application/octet-stream",
        headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "no-store"},
    )


@router.delete("/{thread_id}/{document_id}/{digest}", status_code=204)
def delete_source(
    thread_id: UUID,
    document_id: UUID,
    digest: Digest,
    settings: SettingsDependency,
    storage: StorageDependency,
    internal_api_key: ApiKey,
    tenant_id: TenantId,
    attempt_id: Annotated[UUID | None, Header(alias="X-Source-Attempt-Id")] = None,
) -> Response:
    authorize(settings, internal_api_key, str(tenant_id))
    key = source_key(tenant_id, thread_id, document_id, digest, attempt_id)
    with source_lock(key):
        storage.delete(key)
    return Response(status_code=204)
