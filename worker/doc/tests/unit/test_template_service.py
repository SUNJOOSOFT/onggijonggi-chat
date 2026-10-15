import asyncio
import threading
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile

import pytest
from fastapi import UploadFile
from starlette.datastructures import Headers

from app.core.config import Settings
from app.errors import WorkerError
from app.models.document import TEMPLATE_CONTENT_TYPES
from app.services import document_service
from app.services.document_service import DocumentService


class FakeStorage:
    def __init__(self) -> None:
        self.object_key = ""
        self.content_type = ""
        self.path: Path | None = None

    def upload(self, object_key: str, path: Path, content_type: str) -> None:
        self.object_key = object_key
        self.content_type = content_type
        self.path = path


def document_bytes(suffix: str) -> bytes:
    if suffix == ".pdf":
        return b"%PDF-1.7\n"
    part = {
        ".docx": "word/document.xml",
        ".xlsx": "xl/workbook.xml",
        ".pptx": "ppt/presentation.xml",
    }[suffix]
    buffer = BytesIO()
    with ZipFile(buffer, "w") as archive:
        archive.writestr("[Content_Types].xml", "")
        archive.writestr(part, "")
    return buffer.getvalue()


def make_upload(file_name: str, content_type: str, content: bytes | None = None) -> UploadFile:
    return UploadFile(
        file=BytesIO(content if content is not None else document_bytes(Path(file_name).suffix)),
        filename=file_name,
        headers=Headers({"content-type": content_type}),
    )


@pytest.mark.parametrize(("suffix", "content_type"), TEMPLATE_CONTENT_TYPES.items())
def test_template_service_stores_each_supported_format(suffix: str, content_type: str) -> None:
    storage = FakeStorage()
    service = DocumentService(Settings(internal_api_key="test-key"), storage=storage)

    response = asyncio.run(
        service.save_template(make_upload(f"template{suffix}", content_type), "tenant-a", "request-1")
    )

    assert response.object_key.endswith(f"/{response.template_id}/template{suffix}")
    assert response.content_type == content_type
    assert storage.object_key == response.object_key
    assert storage.content_type == content_type
    assert storage.path is not None
    assert not storage.path.exists()


def test_template_service_rejects_mismatched_mime_type() -> None:
    service = DocumentService(Settings(internal_api_key="test-key"), storage=FakeStorage())

    with pytest.raises(WorkerError, match="Unsupported template format"):
        asyncio.run(
            service.save_template(
                make_upload("template.docx", "application/pdf"),
                "tenant-a",
                "request-1",
            )
        )


def test_template_service_rejects_invalid_document_content() -> None:
    service = DocumentService(Settings(internal_api_key="test-key"), storage=FakeStorage())

    with pytest.raises(WorkerError, match="Template file is not a valid document"):
        asyncio.run(
            service.save_template(
                make_upload(
                    "template.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    b"not-a-docx",
                ),
                "tenant-a",
                "request-1",
            )
        )


def test_template_service_runs_blocking_steps_off_the_event_loop(monkeypatch: pytest.MonkeyPatch) -> None:
    loop_thread = threading.get_ident()
    threads: dict[str, int] = {}

    class RecordingStorage(FakeStorage):
        def upload(self, object_key: str, path: Path, content_type: str) -> None:
            threads["upload"] = threading.get_ident()
            super().upload(object_key, path, content_type)

    def record_validate(path: Path) -> None:
        threads["validate"] = threading.get_ident()

    monkeypatch.setattr(document_service, "validate_template_content", record_validate)
    service = DocumentService(Settings(internal_api_key="test-key"), storage=RecordingStorage())

    async def run() -> int:
        await service.save_template(
            make_upload(
                "template.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
            "tenant-a",
            "request-1",
        )
        return threading.get_ident()

    assert asyncio.run(run()) == loop_thread
    assert threads["validate"] != loop_thread
    assert threads["upload"] != loop_thread
