from app.embeddings import HashingEmbeddingProvider
from app.retrieval import Chunk, MemoryIndexStore, RetrievalEngine
from app.schemas import SearchRequest


def make_chunk(chunk_id: str, kb: str, content: str, chunk_no: int) -> Chunk:
    embedding = HashingEmbeddingProvider()
    return Chunk(chunk_id, kb, "version-1", "测试讲义", chunk_no, content, embedding.embed(content))


def test_hybrid_search_ranks_matching_chunk_first_and_scopes_knowledge_base():
    embedding = HashingEmbeddingProvider()
    store = MemoryIndexStore()
    store.upsert([
        make_chunk("match", "kb-a", "等价类划分是黑盒测试设计用例的方法。", 0),
        make_chunk("other", "kb-a", "软件项目管理需要进行成本估算。", 1),
        make_chunk("foreign", "kb-b", "等价类划分可以减少测试用例数量。", 2),
    ])
    engine = RetrievalEngine(store, embedding)

    results = engine.search(SearchRequest(
        query="什么是等价类划分",
        knowledge_base_id="kb-a",
        document_version_ids=["version-1"],
        method="HYBRID",
        top_k=5,
    ))

    assert results[0].chunk_id == "match"
    assert "foreign" not in {item.chunk_id for item in results}


def test_empty_knowledge_base_returns_no_results():
    engine = RetrievalEngine(MemoryIndexStore(), HashingEmbeddingProvider())
    results = engine.search(SearchRequest(query="问题", knowledge_base_id="kb-none"))
    assert results == []


def test_multiple_scopes_union_is_ranked_once_and_still_requires_version_allowlist():
    store = MemoryIndexStore()
    store.upsert([make_chunk("a", "kb-a", "死锁需要环路等待。", 0),
                  make_chunk("b", "kb-b", "死锁需要互斥。", 1),
                  make_chunk("private", "kb-private", "死锁是多个进程相互等待资源。", 2)])
    engine = RetrievalEngine(store, HashingEmbeddingProvider())
    request = SearchRequest(query="死锁是什么", knowledge_base_id="all", knowledge_base_ids=["kb-a", "kb-b", "kb-a"],
                            document_version_ids=["version-1"])
    assert {e.chunk_id for e in engine.search(request)} == {"a", "b"}
    assert engine.search(request.model_copy(update={"document_version_ids": []})) == []
    assert engine.search(request.model_copy(update={"knowledge_base_ids": []})) == []


def test_generation_context_uses_only_adjacent_overlapping_authorized_chunks():
    overlap = "所有节点的值小于根节点的值，右子树中所有节点的值大于根节点。"
    store = MemoryIndexStore()
    store.upsert([
        make_chunk("previous", "kb-a", "二叉搜索树中，左子树中" + overlap, 0),
        make_chunk("hit", "kb-a", overlap + "\n二叉搜索树从根节点比较键值查找。", 1),
        make_chunk("next", "kb-a", "相邻但没有重叠的另一节，不能自动视为句子续文。", 2),
        make_chunk("private", "private", "机密内容。" + overlap, 0),
    ])
    engine = RetrievalEngine(store, HashingEmbeddingProvider())
    request = SearchRequest(query="二叉搜索树", knowledge_base_id="kb-a", document_version_ids=["version-1"])
    hit = next(e for e in engine.search(request) if e.chunk_id == "hit")
    before = [(c.chunk_id, c.content) for c in store.chunks.values()]
    expanded = engine.expand_context(request, [hit])
    assert [e.chunk_id for e in expanded] == ["hit", "previous"]
    assert expanded[0] == hit
    assert expanded[1].content == store.chunks["previous"].content
    assert expanded[1].fusion_rank == 0  # Context is not a new ranked retrieval hit.
    assert engine.expand_context(request.model_copy(update={"document_version_ids": []}), [hit]) == []
    assert engine.expand_context(request.model_copy(update={"knowledge_base_id": "private"}), [hit]) == []
    assert before == [(c.chunk_id, c.content) for c in store.chunks.values()]


def test_context_does_not_expand_recursively_or_exceed_budget():
    overlap = "这是用于证明相邻片段存在真实重叠的一段足够长的原文。"
    store = MemoryIndexStore()
    store.upsert([make_chunk(str(n), "kb", overlap + "二叉搜索树第" + str(n) + "节。" + overlap, n)
                  for n in range(7)])
    engine = RetrievalEngine(store, HashingEmbeddingProvider())
    request = SearchRequest(query="二叉搜索树", knowledge_base_id="kb", document_version_ids=["version-1"])
    hit = next(e for e in engine.search(request.model_copy(update={"top_k": 7})) if e.chunk_no == 3)
    assert {e.chunk_no for e in engine.expand_context(request, [hit])} == {2, 3, 4}
    assert engine.expand_context(request, [hit], max_characters=len(hit.content)) == [hit]
    assert len(engine.expand_context(request, [hit], max_chunks=2)) == 2


def test_context_rejects_stale_hit_and_other_document_versions():
    overlap = "所有节点的值小于根节点的值，右子树中所有节点的值大于根节点。"
    store = MemoryIndexStore()
    store.upsert([make_chunk("hit", "kb", overlap + "二叉搜索树。", 1)])
    other = make_chunk("old", "kb", "左子树中" + overlap, 0)
    other.document_version_id = "old-version"
    store.upsert([other])
    engine = RetrievalEngine(store, HashingEmbeddingProvider())
    request = SearchRequest(query="二叉搜索树", knowledge_base_id="kb", document_version_ids=["version-1"])
    hit = engine.search(request)[0]
    assert engine.expand_context(request, [hit]) == [hit]
    store.chunks["hit"].content = "Changed after retrieval"
    assert engine.expand_context(request, [hit]) == []


def test_adjacent_hits_do_not_turn_entire_document_into_one_passage():
    from app.retrieval import evidence_passages, passage_text
    overlap = "这是用于证明相邻片段存在真实重叠的一段足够长的原文。"
    store = MemoryIndexStore()
    store.upsert([make_chunk(str(n), "kb", overlap + "二叉搜索树第" + str(n) + "节。" + overlap, n)
                  for n in range(7)])
    engine = RetrievalEngine(store, HashingEmbeddingProvider())
    request = SearchRequest(query="二叉搜索树", knowledge_base_id="kb", document_version_ids=["version-1"], top_k=7)
    anchors = sorted(engine.search(request), key=lambda e: e.chunk_no)[1:6]
    expanded = engine.expand_context(request, anchors)
    groups = evidence_passages(expanded, anchors)
    assert len(groups) == 5 and all(len(group) <= 3 for group in groups)
    for anchor, group in zip(anchors, groups):
        assert [e.chunk_no for e in group] == [anchor.chunk_no - 1, anchor.chunk_no, anchor.chunk_no + 1]
        text = passage_text(group)
        assert all(e.content in text for e in group)


def test_passage_grouping_never_merges_same_title_across_versions():
    from app.retrieval import evidence_passages
    overlap = "二叉搜索树查找节点时，需要从根节点出发比较节点的键值。"
    store = MemoryIndexStore()
    old = make_chunk("old", "kb", "Old version " + overlap, 0)
    current = make_chunk("current", "kb", overlap + "New version", 1)
    current.document_version_id = "v2"
    store.upsert([old, current])
    engine = RetrievalEngine(store, HashingEmbeddingProvider())
    request = SearchRequest(query="二叉搜索树", knowledge_base_id="kb", document_version_ids=["version-1", "v2"])
    hits = engine.search(request)
    assert len(evidence_passages(hits, hits)) == 2
