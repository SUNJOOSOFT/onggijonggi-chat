import hmac
from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError

from app.api.routes.documents import router as documents_router
from app.api.routes.source_files import SOURCE_PATH_PREFIX, router as source_files_router
from app.core.config import Settings
from app.errors import WorkerError, error_response


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 필수 설정(INTERNAL_API_KEY)이 없으면 기동 자체를 실패시킨다 — 요청 단위로 Settings를
    # 만들면 기동도 헬스체크도 통과한 뒤 모든 문서 요청이 500이 되어 원인 추적이 어렵다.
    Settings()
    yield


app = FastAPI(title="document-worker", docs_url=None, redoc_url=None, lifespan=lifespan)
app.include_router(documents_router)
app.include_router(source_files_router)


# 원본 PUT의 파일 파트는 Starlette가 크기 제한 없이 임시 파일(tmpfs)로 스풀한 뒤에야 라우트가 key·크기를 본다.
# 그래서 원본 경로는 본문을 읽기 전에 key와 선언된 길이를 먼저 확인한다. 라우트의 같은 검사는 그대로 둔다.
_MULTIPART_OVERHEAD_BYTES = 64 * 1024


@app.middleware("http")
async def guard_thread_sources(request: Request, call_next):
    if request.url.path.startswith(SOURCE_PATH_PREFIX):
        settings = Settings()
        supplied = request.headers.get("X-Internal-Api-Key", "").encode("latin-1")
        if not hmac.compare_digest(supplied, settings.internal_api_key.encode()):
            return error_response(request, WorkerError(401, "UNAUTHORIZED", "Invalid internal API key"))
        length = request.headers.get("Content-Length")
        if length and length.isdigit() and int(length) > settings.max_source_bytes + _MULTIPART_OVERHEAD_BYTES:
            return error_response(request, WorkerError(413, "FILE_TOO_LARGE", "Document source exceeds upload limit"))
    return await call_next(request)


@app.middleware("http")
async def add_request_id(request: Request, call_next):
    request.state.request_id = request.headers.get("X-Request-Id") or uuid4().hex
    return await call_next(request)


@app.exception_handler(WorkerError)
async def handle_worker_error(request: Request, error: WorkerError):
    return error_response(request, error)


@app.exception_handler(RequestValidationError)
async def handle_validation_error(request: Request, error: RequestValidationError):
    return error_response(request, WorkerError(400, "VALIDATION_ERROR", "Document request is invalid"))


@app.get("/health/live")
def liveness() -> dict[str, str]:
    return {"status": "ok"}
