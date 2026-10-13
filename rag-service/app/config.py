from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    service_token: str = ""
    index_backend: str = "memory"
    index_path: str = "../.runtime/rag-index.json"
    embedding_provider: str = "hashing"
    embedding_dimensions: int = 384
    chat_provider: str = "disabled"
    chat_base_url: str = ""
    chat_api_key: str = ""
    chat_model: str = ""
    answer_mode: Literal["", "extractive", "generated"] = ""
    chat_timeout_seconds: float = 25.0
    es_url: str = ""
    es_index: str = "rag-document-chunks"
    min_evidence_score: float = 0.12
    bm25_k1: float = 1.5
    bm25_b: float = 0.75

    model_config = SettingsConfigDict(env_prefix="RAG_", env_file=".env", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
