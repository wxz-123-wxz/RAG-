from contextlib import asynccontextmanager
from time import perf_counter
from uuid import uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response

from app.config import get_settings
from app.index_store import JsonIndexStore
from app.retrieval import MemoryIndexStore
from app.schemas import GenerateRequest, GenerateResponse, IndexDocumentRequest, SearchRequest, SearchResponse
from app.service import RagService
from app.providers import ChatProviderError
from app.diagnostics import request_trace_id


settings = get_settings()
store = JsonIndexStore(settings.index_path) if settings.index_backend.lower() == "json" else MemoryIndexStore()
rag_service = RagService(settings, store)


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield


app = FastAPI(title="RAG Question Answering Service", version="0.1.0", lifespan=lifespan)


@app.middleware("http")
async def attach_trace_id(request: Request, call_next):
    incoming = request.headers.get("X-Trace-Id", "")
    trace_id = incoming if 8 <= len(incoming) <= 64 and incoming.replace("-", "").replace("_", "").isalnum() else uuid4().hex
    request.state.trace_id = trace_id
    started = perf_counter()
    token = request_trace_id.set(trace_id)
    try:
        response = await call_next(request)
    finally:
        request_trace_id.reset(token)
    response.headers["X-Trace-Id"] = trace_id
    response.headers["X-Process-Time-Ms"] = str(round((perf_counter() - started) * 1000))
    return response


def require_internal_token(authorization: str | None = Header(default=None)) -> None:
    if not settings.service_token:
        return
    if authorization != f"Bearer {settings.service_token}":
        raise HTTPException(status_code=401, detail="Invalid service credentials")


@app.get("/health/live")
def live() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready")
def ready() -> dict[str, str | int | bool]:
    return {"status": "UP", "indexBackend": settings.index_backend, "indexedChunks": store.count(),
            "answerMode": settings.answer_mode or ("extractive" if settings.chat_provider == "extractive" else "generated"),
            "chatConfigured": rag_service.chat.enabled, "chatModel": settings.chat_model if rag_service.chat.enabled else "",
            "embeddingProvider": "hashing-tfidf-baseline",
            "reranker": "not-configured"}


@app.post("/internal/v1/documents/index", dependencies=[Depends(require_internal_token)])
def index_document(payload: IndexDocumentRequest) -> dict[str, int | str]:
    if payload.document_version_id == "" or not payload.chunks:
        raise HTTPException(status_code=422, detail="Document version and chunks are required")
    try:
        count = rag_service.index_document(payload.knowledge_base_id or "unscoped", payload)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    return {"documentVersionId": payload.document_version_id, "indexedChunks": count}


@app.post("/internal/v1/knowledge-bases/{knowledge_base_id}/documents/index", dependencies=[Depends(require_internal_token)])
def index_document_in_knowledge_base(knowledge_base_id: str, payload: IndexDocumentRequest) -> dict[str, int | str]:
    payload = payload.model_copy(update={"knowledge_base_id": knowledge_base_id})
    try:
        count = rag_service.index_document(knowledge_base_id, payload)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    return {"documentVersionId": payload.document_version_id, "indexedChunks": count}


@app.delete("/internal/v1/document-versions/{version_id}/index", dependencies=[Depends(require_internal_token)])
def delete_document_index(version_id: str) -> dict[str, int | str]:
    return {"documentVersionId": version_id, "deletedChunks": store.delete_version(version_id)}


@app.post("/internal/v1/retrieval/search", response_model=SearchResponse,
          dependencies=[Depends(require_internal_token)])
def search(payload: SearchRequest) -> SearchResponse:
    return rag_service.search(payload)


@app.post("/internal/v1/answers/generate", response_model=GenerateResponse,
          dependencies=[Depends(require_internal_token)])
def generate(payload: GenerateRequest, response: Response) -> GenerateResponse:
    try:
        return rag_service.generate(payload)
    except ChatProviderError as error:
        missing = error.code == "CHAT_MODEL_NOT_CONFIGURED"
        raise HTTPException(status_code=503 if missing else 502, detail={
            "code": error.code,
            "message": "生成模型尚未配置" if missing else "生成模型调用失败或引用无效",
        }) from error
