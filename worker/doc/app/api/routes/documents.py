import hmac
import re
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, Header, Query, Request, Response, UploadFile
from pydantic import TypeAdapter, ValidationError

from app.core.config import Settings
from app.core.request_context import request_id
from app.errors import WorkerError
from app.models.document import DocumentRequest, DocumentResponse, TemplateResponse
from app.renderers.docx import DOCX_CONTENT_TYPE
from app.services.document_service import DocumentService
from app.services.docx_revision import DocumentEdit, revise

_TENANT_ID = re.compile(r"^[A-Za-z0-9_-]{1,100}$")

router = APIRouter(prefix="/api/v1/documents", tags=["documents"])
_EDITS = TypeAdapter(list[DocumentEdit])


def get_settings() -> Settings:
    return Settings()


def get_document_service(settings: Annotated[Settings, Depends(get_settings)]) -> DocumentService:
    return DocumentService(settings)


def authorize(settings: Settings, internal_api_key: str, tenant_id: str) -> None:
    # str 비교는 비ASCII 헤더 값(latin-1로 디코딩됨)에서 TypeError(500)가 나므로 bytes로 비교한다.
    if not hmac.compare_digest(internal_api_key.encode(), settings.internal_api_key.encode()):
        raise WorkerError(401, "UNAUTHORIZED", "Invalid internal API key")
    if not _TENANT_ID.fullmatch(tenant_id):
        raise WorkerError(400, "VALIDATION_ERROR", "X-Tenant-Id is invalid")


@router.post("", status_code=201, response_model=DocumentResponse)
def create_document(
    document_request: DocumentRequest,
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    service: Annotated[DocumentService, Depends(get_document_service)],
    internal_api_key: Annotated[str, Header(alias="X-Internal-Api-Key")],
    tenant_id: Annotated[str, Header(alias="X-Tenant-Id")],
) -> DocumentResponse:
    authorize(settings, internal_api_key, tenant_id)
    return service.create(document_request, tenant_id, request_id(request))


@router.get("/files")
def read_generated_document(
    settings: Annotated[Settings, Depends(get_settings)],
    service: Annotated[DocumentService, Depends(get_document_service)],
    internal_api_key: Annotated[str, Header(alias="X-Internal-Api-Key")],
    tenant_id: Annotated[str, Header(alias="X-Tenant-Id")],
    object_key: Annotated[str, Query(alias="objectKey", min_length=1, max_length=1024)],
) -> Response:
    """생성 응답의 objectKey로 저장된 문서를 돌려준다. 방 권한과 내려받는 이름은 BFF가 정한다 — 내부 API가 업로드 내용을 실행하게 하지 않는다."""
    authorize(settings, internal_api_key, tenant_id)
    return Response(
        content=service.read_output(object_key, tenant_id),
        media_type="application/octet-stream",
        headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "no-store"},
    )


@router.post("/templates", status_code=201, response_model=TemplateResponse)
async def save_template(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    service: Annotated[DocumentService, Depends(get_document_service)],
    internal_api_key: Annotated[str, Header(alias="X-Internal-Api-Key")],
    tenant_id: Annotated[str, Header(alias="X-Tenant-Id")],
    template: Annotated[UploadFile, File()],
) -> TemplateResponse:
    authorize(settings, internal_api_key, tenant_id)
    return await service.save_template(template, tenant_id, request_id(request))


@router.post("/revisions")
def revise_document(
    settings: Annotated[Settings, Depends(get_settings)],
    internal_api_key: Annotated[str, Header(alias="X-Internal-Api-Key")],
    tenant_id: Annotated[str, Header(alias="X-Tenant-Id")],
    file: Annotated[UploadFile, File()],
    edits: Annotated[str, Form()],
) -> Response:
    """원본 docx의 지정한 문장만 바꾸고 신구조문 대비표를 붙인 docx를 돌려준다. 저장하지 않는다 — 방 문서 등록은 BFF가 한다."""
    authorize(settings, internal_api_key, tenant_id)
    content = file.file.read(settings.max_source_bytes + 1)
    if not content:
        raise WorkerError(400, "EMPTY_SOURCE", "Document source must not be empty")
    if len(content) > settings.max_source_bytes:
        raise WorkerError(413, "FILE_TOO_LARGE", "Document source exceeds upload limit")
    try:
        parsed = _EDITS.validate_json(edits)
    except ValidationError as error:
        raise WorkerError(400, "VALIDATION_ERROR", "edits must be a JSON list of {before, after}") from error
    return Response(
        content=revise(content, parsed),
        media_type=DOCX_CONTENT_TYPE,
        headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "no-store"},
    )
