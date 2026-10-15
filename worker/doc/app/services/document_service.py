from datetime import UTC, datetime
from pathlib import Path
from secrets import token_bytes
from tempfile import TemporaryDirectory
from zipfile import BadZipFile, ZipFile

from fastapi import UploadFile
from starlette.concurrency import run_in_threadpool

from app.core.config import Settings
from app.errors import WorkerError
from app.models.document import (
    TEMPLATE_CONTENT_TYPES,
    DocumentRequest,
    DocumentResponse,
    TemplateResponse,
    validate_file_name,
)
from app.renderers.docx import DOCX_CONTENT_TYPE, DocxRenderer
from app.renderers.markdown import MarkdownRenderer
from app.services.pdf_converter import PdfConverter
from app.storage.seaweedfs import SeaweedFsStorage

_ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_OFFICE_TEMPLATE_PARTS = {
    ".docx": "word/document.xml",
    ".xlsx": "xl/workbook.xml",
    ".pptx": "ppt/presentation.xml",
}


def new_ulid() -> str:
    value = (int(datetime.now(UTC).timestamp() * 1000) << 80) | int.from_bytes(token_bytes(10))
    characters = []
    for _ in range(26):
        characters.append(_ULID_ALPHABET[value & 31])
        value >>= 5
    return "".join(reversed(characters))


def validate_template_content(path: Path) -> None:
    suffix = path.suffix.lower()
    try:
        if suffix == ".pdf":
            if not path.read_bytes().startswith(b"%PDF-"):
                raise ValueError("PDF header is missing")
            return
        with ZipFile(path) as archive:
            required_part = _OFFICE_TEMPLATE_PARTS[suffix]
            if required_part not in archive.namelist():
                raise ValueError(f"{required_part} is missing")
    except (BadZipFile, OSError, ValueError) as error:
        raise WorkerError(422, "UNPROCESSABLE_DOCUMENT", "Template file is not a valid document") from error


class DocumentService:
    def __init__(
        self,
        settings: Settings,
        storage: SeaweedFsStorage | None = None,
        pdf_converter: PdfConverter | None = None,
    ) -> None:
        self._settings = settings
        self._renderer = DocxRenderer()
        self._markdown_renderer = MarkdownRenderer()
        self._storage = storage or SeaweedFsStorage(
            settings.seaweed_filer_url,
            settings.render_timeout_seconds,
        )
        self._pdf_converter = pdf_converter or PdfConverter(settings.render_timeout_seconds)

    def create(self, request: DocumentRequest, tenant_id: str, request_id: str) -> DocumentResponse:
        file_id = new_ulid()
        created_at = datetime.now(UTC)
        object_key = "/".join(
            (
                self._settings.seaweed_root_prefix,
                tenant_id,
                created_at.strftime("%Y"),
                created_at.strftime("%m"),
                created_at.strftime("%d"),
                file_id,
                request.file_name,
            )
        )
        with TemporaryDirectory(prefix="document-worker-") as temporary_directory:
            output_dir = Path(temporary_directory)
            if request.output_format == "MD":
                rendered = self._markdown_renderer.render(request, output_dir)
            else:
                source_file_name = Path(request.file_name).with_suffix(".docx").name
                rendered = self._renderer.render(request, output_dir, source_file_name)
            size = rendered.path.stat().st_size
            if size > self._settings.max_output_bytes:
                raise WorkerError(413, "OUTPUT_TOO_LARGE", "Generated document exceeds the maximum size")
            if request.output_format == "PDF":
                rendered = self._pdf_converter.convert(rendered, output_dir, request.file_name)
                size = rendered.path.stat().st_size
                if size > self._settings.max_output_bytes:
                    raise WorkerError(413, "OUTPUT_TOO_LARGE", "Generated document exceeds the maximum size")
            self._storage.upload(object_key, rendered.path, rendered.content_type)

        return DocumentResponse(
            file_id=file_id,
            file_name=rendered.file_name,
            object_key=object_key,
            content_type=rendered.content_type,
            size=size,
            created_at=created_at,
            request_id=request_id,
        )

    async def save_template(
        self,
        template: UploadFile,
        tenant_id: str,
        request_id: str,
    ) -> TemplateResponse:
        file_name = template.filename or ""
        try:
            validate_file_name(file_name)
        except ValueError as error:
            raise WorkerError(400, "VALIDATION_ERROR", str(error)) from error
        content_type = TEMPLATE_CONTENT_TYPES.get(Path(file_name).suffix.lower())
        if content_type is None or template.content_type != content_type:
            raise WorkerError(415, "UNSUPPORTED_FORMAT", "Unsupported template format")

        template_id = new_ulid()
        created_at = datetime.now(UTC)
        object_key = "/".join(
            (
                "templates",
                tenant_id,
                created_at.strftime("%Y"),
                created_at.strftime("%m"),
                created_at.strftime("%d"),
                template_id,
                file_name,
            )
        )
        with TemporaryDirectory(prefix="document-template-") as temporary_directory:
            path = Path(temporary_directory) / file_name
            size = 0
            with path.open("wb") as destination:
                while chunk := await template.read(1024 * 1024):
                    size += len(chunk)
                    if size > self._settings.max_output_bytes:
                        raise WorkerError(413, "OUTPUT_TOO_LARGE", "Template exceeds the maximum size")
                    destination.write(chunk)
            await run_in_threadpool(validate_template_content, path)
            await run_in_threadpool(self._storage.upload, object_key, path, content_type)

        return TemplateResponse(
            template_id=template_id,
            file_name=file_name,
            object_key=object_key,
            content_type=content_type,
            size=size,
            created_at=created_at,
            request_id=request_id,
        )
