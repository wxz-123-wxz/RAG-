from typing import Literal

from pydantic import BaseModel, Field, ConfigDict, model_validator


class IndexDocumentRequest(BaseModel):
    knowledge_base_id: str = Field(default="", max_length=64)
    document_version_id: str = Field(min_length=1, max_length=64)
    document_name: str = Field(min_length=1, max_length=255)
    chunks: list[str] = Field(min_length=1, max_length=5000)
    chunk_size: int = Field(default=600, ge=100, le=4000)
    chunk_overlap: int = Field(default=100, ge=0, le=1000)
    language: str = Field(default="zh", max_length=32)


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=2000)
    knowledge_base_id: str = Field(min_length=1, max_length=64)
    knowledge_base_ids: list[str] = Field(default_factory=list, max_length=1000)
    top_k: int = Field(default=5, ge=1, le=50)
    method: Literal["VECTOR", "HYBRID", "HYBRID_RERANK"] = "HYBRID"
    document_version_ids: list[str] = Field(default_factory=list, max_length=1000)
    rrf_k: int = Field(default=60, ge=1, le=500)
    vector_weight: float = Field(default=1.0, ge=0, le=10)
    bm25_weight: float = Field(default=1.0, ge=0, le=10)


class Evidence(BaseModel):
    chunk_id: str
    document_version_id: str
    document_name: str
    chunk_no: int
    content: str
    score: float
    vector_score: float = 0.0
    bm25_score: float = 0.0
    bm25_rank: int | None = None
    vector_rank: int | None = None
    fusion_rank: int


class SearchResponse(BaseModel):
    query: str
    method: str
    evidence: list[Evidence]
    sufficient: bool
    reason: str
    latency_ms: int


class GenerateRequest(SearchRequest):
    answer_mode: Literal["default", "extractive", "generated", "general"] = "default"
    question: str = Field(min_length=1, max_length=2000)
    history: list[dict[str, str]] = Field(default_factory=list, max_length=20)
    evidence_threshold: float = Field(default=0.12, ge=0, le=1)
    retrieval_threshold: float = Field(default=0.1, ge=0, le=1)


class Citation(BaseModel):
    number: int
    chunk_id: str
    document_version_id: str
    document_name: str
    chunk_no: int
    quote: str


class GenerateResponse(BaseModel):
    status: Literal["ANSWERED", "REFUSED"]
    answer: str
    citations: list[Citation]
    query: str
    evidence_count: int
    latency_ms: int
    evidence_reason: str
    model: str
    answer_origin: Literal["KNOWLEDGE_BASE", "GENERAL", "NONE"] = "KNOWLEDGE_BASE"
    evidence_state: Literal["UNASSESSED", "MODEL_REPORTED_COMPLETE", "MODEL_REPORTED_PARTIAL", "INSUFFICIENT", "NOT_APPLICABLE"] = "UNASSESSED"


class GroundedClaim(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=8000)
    citations: list[int] = Field(min_length=1, max_length=5)


class GroundedOutput(BaseModel):
    """Model-reported coverage, never a calibrated confidence probability."""
    model_config = ConfigDict(extra="forbid")
    coverage: Literal["complete", "partial", "insufficient"]
    claims: list[GroundedClaim] = Field(max_length=30)
    missing_aspects: list[str] = Field(max_length=20)

    @model_validator(mode="after")
    def validate_coverage(self):
        if any(not x.strip() or len(x) > 1000 for x in self.missing_aspects):
            raise ValueError("Missing aspects must be bounded nonempty descriptions")
        if self.coverage == "insufficient" and self.claims:
            raise ValueError("Insufficient evidence cannot support claims")
        if self.coverage != "insufficient" and not self.claims:
            raise ValueError("Supported answers require cited claims")
        if self.coverage == "partial" and not any(x.strip() for x in self.missing_aspects):
            raise ValueError("Partial coverage must state missing aspects")
        if self.coverage == "complete" and self.missing_aspects:
            raise ValueError("Complete coverage cannot contain missing aspects")
        return self


class CoverageQuote(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source: int = Field(ge=1)
    quote: str = Field(min_length=8, max_length=8000)


class CoverageAspect(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: int = Field(ge=1)
    status: Literal["supported", "insufficient"]
    text: str = Field(max_length=8000)
    evidence: list[CoverageQuote] = Field(max_length=5)

    @model_validator(mode="after")
    def validate_support(self):
        if self.status == "supported" and (not self.text.strip() or not self.evidence):
            raise ValueError("Supported aspects require text and source quotes")
        if self.status == "insufficient" and (self.text.strip() or self.evidence):
            raise ValueError("Unsupported aspects must not contain an answer")
        return self


class CoverageReview(BaseModel):
    model_config = ConfigDict(extra="forbid")
    aspects: list[CoverageAspect] = Field(min_length=1, max_length=12)
