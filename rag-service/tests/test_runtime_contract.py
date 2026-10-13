import pytest
import json
from app.config import Settings
from app.providers import ChatProviderError
from app.retrieval import MemoryIndexStore
from app.schemas import GenerateRequest, IndexDocumentRequest, SearchRequest
from app.service import RagService


class EvidenceEcho:
    """Test double only. Never used as the deployed chat provider."""
    def generate(self, question, evidence, history):
        return json.dumps({"coverage": "complete", "claims": [{"text": evidence[0]["content"], "citations": [1]}], "missing_aspects": []})


def indexed_service():
    service = RagService(Settings(chat_provider="disabled", answer_mode="generated", chat_base_url="", chat_api_key="", chat_model=""), MemoryIndexStore())
    service.index_document("kb-1", IndexDocumentRequest(document_version_id="v1",
                          document_name="testing.txt", chunks=["alpha beta software testing"]))
    return service


@pytest.mark.parametrize("method", ["VECTOR", "HYBRID", "HYBRID_RERANK"])
def test_matching_evidence_reaches_provider_without_rrf_threshold_mixup(method):
    service = indexed_service()
    service.chat = EvidenceEcho()
    result = service.generate(GenerateRequest(question="alpha beta software testing",
        query="alpha beta software testing", knowledge_base_id="kb-1", document_version_ids=["v1"],
        method=method, retrieval_threshold=.05))
    assert result.status == "ANSWERED"
    assert result.citations[0].document_version_id == "v1"
    assert result.citations[0].chunk_no == 0


def test_empty_allow_list_cannot_retrieve_drafts():
    service = indexed_service()
    for versions in ([], ["other-version"]):
        result = service.search(SearchRequest(query="alpha beta", knowledge_base_id="kb-1",
                                              document_version_ids=versions))
        assert result.evidence == []
        assert not result.sufficient


def test_unrelated_hybrid_query_is_not_sufficient():
    result = indexed_service().search(SearchRequest(query="xylophone", knowledge_base_id="kb-1",
                                                    document_version_ids=["v1"]))
    assert not result.sufficient


def test_explicit_followup_uses_latest_question_but_topic_switch_does_not():
    history = [{"role": "user", "content": "old topic"}, {"role": "user", "content": "等价类划分是什么"}]
    assert RagService._rewrite_query("它有什么特点？", history) == "等价类划分是什么，它有什么特点？"
    assert RagService._rewrite_query("六边形架构的端口是什么？", history) == "六边形架构的端口是什么？"


def test_explicit_extractive_mode_works_even_if_external_provider_is_configured():
    service = indexed_service()
    service.settings.chat_provider = "openai-compatible"
    class NoModelAllowed:
        def generate(self, *args):
            raise AssertionError("Explicit extractive mode must bypass model generation")
    service.chat = NoModelAllowed()
    result = service.generate(GenerateRequest(question="alpha beta", query="alpha beta",
        knowledge_base_id="kb-1", document_version_ids=["v1"], answer_mode="extractive"))
    assert result.model == "extractive-no-llm"


def test_explicit_generated_mode_never_silently_falls_back_to_excerpts():
    service = indexed_service()
    service.settings.answer_mode = "extractive"
    with pytest.raises(ChatProviderError) as error:
        service.generate(GenerateRequest(question="alpha beta", query="alpha beta",
            knowledge_base_id="kb-1", document_version_ids=["v1"], answer_mode="generated"))
    assert error.value.code == "CHAT_MODEL_NOT_CONFIGURED"


def test_missing_model_is_dependency_error_not_correct_refusal():
    with pytest.raises(ChatProviderError, match="not configured"):
        indexed_service().generate(GenerateRequest(question="alpha beta software testing",
            query="alpha beta software testing", knowledge_base_id="kb-1", document_version_ids=["v1"]))


def test_extractive_mode_returns_exact_sources_without_calling_model():
    service = RagService(Settings(chat_provider="extractive", answer_mode="extractive"), MemoryIndexStore())
    text = "alpha beta software testing. Source contains its own marker [99]."
    service.index_document("kb-1", IndexDocumentRequest(document_version_id="v1", document_name="source.txt", chunks=[text]))
    class NoModelAllowed:
        def generate(self, *args):
            raise AssertionError("Extractive mode must never call a model")
    service.chat = NoModelAllowed()
    result = service.generate(GenerateRequest(question="alpha beta software testing", query="alpha beta software testing",
        knowledge_base_id="kb-1", document_version_ids=["v1"]))
    assert result.status == "ANSWERED"
    assert result.model == "extractive-no-llm"
    assert result.evidence_reason == "EXTRACTIVE_EVIDENCE"
    assert "未调用生成模型" in result.answer
    assert result.citations[0].quote == text


@pytest.mark.parametrize("mode", ["extractive", "generated"])
@pytest.mark.parametrize("question,versions", [("xylophonequasarzz", ["v1"]), ("alpha beta", [])])
def test_no_evidence_refuses_before_any_model_call(mode, question, versions):
    service = indexed_service()
    class NoModelAllowed:
        def generate(self, *args):
            raise AssertionError("No evidence must prevent any model call")
    service.chat = NoModelAllowed()
    result = service.generate(GenerateRequest(question=question, query=question,
        knowledge_base_id="kb-1", document_version_ids=versions, answer_mode=mode))
    assert result.status == "REFUSED"
    assert result.citations == []
    assert result.model == "disabled"


@pytest.mark.parametrize("answer,code", [
    (json.dumps({"coverage": "complete", "claims": [{"text": "Unsupported", "citations": [99]}], "missing_aspects": []}), "CHAT_MODEL_INVALID_CITATION"),
    ("Uncited unstructured statement", "CHAT_MODEL_INVALID_RESPONSE"),
])
def test_invalid_or_missing_model_citations_fail_closed(answer, code):
    service = indexed_service()
    class InvalidChat:
        def generate(self, *args):
            return answer
    service.chat = InvalidChat()
    with pytest.raises(ChatProviderError) as error:
        service.generate(GenerateRequest(question="alpha beta software testing", query="alpha beta software testing",
            knowledge_base_id="kb-1", document_version_ids=["v1"]))
    assert error.value.code == code
