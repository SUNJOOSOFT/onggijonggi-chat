from pathlib import Path
from urllib.parse import quote

import httpx

from app.errors import WorkerError


class SeaweedFsStorage:
    def __init__(self, filer_url: str, timeout_seconds: int) -> None:
        self._filer_url = filer_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def upload(self, object_key: str, path: Path, content_type: str) -> None:
        url = f"{self._filer_url}/{quote(object_key, safe='/')}"
        try:
            with path.open("rb") as file:
                response = httpx.post(
                    url,
                    files={"file": (path.name, file, content_type)},
                    timeout=self._timeout_seconds,
                )
            response.raise_for_status()
        except (OSError, httpx.HTTPError) as error:
            raise WorkerError(503, "STORAGE_UNAVAILABLE", "Document storage is unavailable") from error

    def upload_bytes(self, object_key: str, content: bytes) -> None:
        """RAG 원본은 변환하지 않고 저장한다. 파일명은 저장 경로에 사용하지 않는다."""
        url = f"{self._filer_url}/{quote(object_key, safe='/')}"
        try:
            response = httpx.post(
                url,
                files={"file": ("source", content, "application/octet-stream")},
                timeout=self._timeout_seconds,
            )
            response.raise_for_status()
        except httpx.HTTPError as error:
            raise WorkerError(503, "STORAGE_UNAVAILABLE", "Document storage is unavailable") from error

    def read(self, object_key: str, max_bytes: int) -> bytes:
        """저장소 응답도 크기를 제한한다. BFF가 방 인가를 확인한 뒤에만 호출한다."""
        url = f"{self._filer_url}/{quote(object_key, safe='/')}"
        try:
            with httpx.stream("GET", url, timeout=self._timeout_seconds) as response:
                if response.status_code == 404:
                    raise WorkerError(404, "SOURCE_NOT_FOUND", "Document source is unavailable")
                response.raise_for_status()
                content = bytearray()
                for chunk in response.iter_bytes(chunk_size=65_536):
                    if len(content) + len(chunk) > max_bytes:
                        raise WorkerError(503, "STORAGE_UNAVAILABLE", "Document source exceeds storage limit")
                    content.extend(chunk)
                return bytes(content)
        except httpx.HTTPError as error:
            raise WorkerError(503, "STORAGE_UNAVAILABLE", "Document storage is unavailable") from error

    def delete(self, object_key: str) -> None:
        """이미 지워진 원본도 성공으로 처리해 내구성 있는 정리 작업의 재시도를 허용한다."""
        url = f"{self._filer_url}/{quote(object_key, safe='/')}"
        try:
            response = httpx.delete(url, timeout=self._timeout_seconds)
            if response.status_code != 404:
                response.raise_for_status()
        except httpx.HTTPError as error:
            raise WorkerError(503, "STORAGE_UNAVAILABLE", "Document storage is unavailable") from error
