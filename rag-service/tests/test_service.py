from app.config import Settings
from app.retrieval import MemoryIndexStore
from app.schemas import GenerateRequest, IndexDocumentRequest
from app.service import REFUSAL_TEXT, RagService


def test_indexing_is_idempotent_for_document_version():
    settings = Settings()
    store = MemoryIndexStore()
    service = RagService(settings, store)
    request = IndexDocumentRequest(
        document_version_id="version-1",
        document_name="testing.txt",
        chunks=["等价类划分是测试技术", "边界值分析关注取值边界"],
    )

    assert service.index_document("kb-1", request) == 2
    assert service.index_document("kb-1", request) == 2
    assert store.count() == 2


def test_generation_refuses_when_knowledge_base_has_no_evidence():
    service = RagService(Settings(), MemoryIndexStore())
    result = service.generate(GenerateRequest(
        question="软件测试中的等价类是什么？",
        query="等價類",
        knowledge_base_id="kb-1",
    ))

    assert result.status == "REFUSED"
    assert result.answer == REFUSAL_TEXT
    assert result.citations == []


def test_citation_filter_drops_unknown_indexes():
    assert RagService._keep_valid_citations("依据[1]和[9]", 1) == "依据[1]和"


def test_generated_context_preserves_exact_chunk_quotes_and_extractive_ranking():
    import json
    from app.schemas import SearchRequest
    service = RagService(Settings(), MemoryIndexStore())
    overlap = "所有节点的值小于根节点的值，右子树中所有节点的值大于根节点。"
    chunks = ["二叉搜索树中，左子树中" + overlap, overlap + "\n二叉搜索树从根查找。"]
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="树.md", chunks=chunks))
    hit = next(e for e in service.retrieval.search(SearchRequest(query="二叉搜索树", knowledge_base_id="kb",
        document_version_ids=["v"])) if e.chunk_no == 1)
    service.retrieval.search = lambda request: [hit]
    class Model:
        def generate(self, question, evidence, history):
            assert len(evidence) == 1
            assert evidence[0]['content'] == chunks[0] + "\n二叉搜索树从根查找。"
            return json.dumps({'coverage':'complete','claims':[{'text':'左子树中所有节点的值小于根节点。','citations':[1]}],'missing_aspects':[]})
    service.chat = Model()
    request = GenerateRequest(query="二叉搜索树", question="二叉搜索树", knowledge_base_id="kb", document_version_ids=["v"], top_k=1, answer_mode="generated")
    result = service.generate(request)
    assert result.evidence_count == 2
    assert {c.chunk_no:c.quote for c in result.citations} == dict(enumerate(chunks))
    assert '[2][1]' in result.answer
    extractive = service.generate(request.model_copy(update={"answer_mode":"extractive"}))
    assert extractive.evidence_count == 1 and extractive.citations[0].quote == chunks[1]
