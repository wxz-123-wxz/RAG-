import pytest

from app.config import Settings
from app.relevance import has_relevant_evidence, is_concept_overview, query_subjects
from app.retrieval import MemoryIndexStore
from app.schemas import GenerateRequest, IndexDocumentRequest, SearchRequest
from app.service import RagService


@pytest.mark.parametrize("query,content", [
    ("B树是什么", "如果你只对不会离开你电脑的提交执行变基，那就不会有事。git checkout -b sf"),
    ("B树是什么", "AVL 树和二叉搜索树支持查找，变量 b 保存节点。"),
    ("B树是什么", "B+树是一种多路搜索树。"),
    ("B+树是什么", "B树是一种平衡的多路搜索树。"),
    ("死锁是什么", "软件项目中什么是需求变更，如何管理配置？"),
    ("什么是红黑树", "分支树是用来记录提交历史的结构。"),
    ("TCP是什么", "配置 TCP_NODELAY、HTTP 和 UDP 时执行这些操作。"),
    ("C++是什么", "C# 是一种编程语言，C 语言也被广泛使用。"),
    ("量子纠缠是什么", "什么是软件测试？这是验证程序是否符合需求的活动。"),
    ("B树是什么", "# B树是什么\n> 来源：https://example.org/b-tree\n这里实际仅说明如何使用 Git。"),
    ("死锁是什么", "* [死锁](#死锁)\n本文介绍 Git 分支。"),
    ("是什么", "这是什么东西，是软件课程。"),
])
def test_unrelated_or_metadata_only_evidence_is_rejected(query, content):
    assert not has_relevant_evidence(query, content)


@pytest.mark.parametrize("query,content", [
    ("B树是什么", "B 树是一种平衡的多路搜索树。"),
    ("B-tree是什么", "B树通过一个节点存放多个关键字。"),
    ("B+树是什么", "B+ tree 的所有数据位于叶子节点。"),
    ("什么是等价类划分", "等价类划分是一种黑盒测试用例设计方法。"),
    ("二分查找的时间复杂度是什么？", "二分查找每次将区间减半，时间复杂度为 O(log n)。"),
    ("死锁的必要条件有哪些？", "死锁的必要条件包含互斥、占有且等待、不可抢占、循环等待。"),
    ("TCP 三次握手的过程是什么", "TCP 建立连接使用三次握手，客户端发送 SYN。"),
    ("进程和线程有什么区别？", "线程是调度的基本单位，线程共享所属进程资源。"),
    ("黑盒测试与白盒测试有什么区别？", "黑盒测试不考虑程序内部结构。"),
    ("Git 分支是什么", "Git 分支本质上仅仅是指向提交对象的可变指针。"),
    ("中间件是什么", "中间件提供应用间通信和公共服务。"),
    ("C++是什么", "C++提供类、模板和多种编程范式。"),
    ("栈是什么", "栈遵循后进先出规则。"),
    ("堆", "堆是一种特殊的完全二叉树。"),
    ("B＋树是什么", "B+树的叶子节点存储数据。"),
    ("What is software testing?", "Software testing checks expected behavior."),
    ("二叉树有哪些遍历方式？", "二叉树常见的遍历方式包括层序遍历、前序遍历、中序遍历和后序遍历等。"),
    ("冯·诺依曼结构由哪些部件组成？", "冯诺依曼结构由运算器、控制器、存储器、输入设备和输出设备组成。"),
    ("代码审查应该关注哪些方面？", "代码审查关注设计、功能、复杂度和测试。"),
    ("TCP 三次握手的过程是什么？", "## TCP 的三次握手\n三次握手建立连接，客户端发送 SYN，服务端回复 SYN ACK，客户端确认。"),
])
def test_substantive_queries_match_real_body_text(query, content):
    assert has_relevant_evidence(query, content)


@pytest.mark.parametrize("query", ["B树是什么", "红黑树是什么", "死锁的条件有哪些", "TCP和UDP有什么区别", "怎么制作蛋糕", "量子纠缠是什么"])
@pytest.mark.parametrize("mode", ["extractive", "generated"])
def test_irrelevant_candidates_refuse_before_calling_model(query, mode):
    service = RagService(Settings(), MemoryIndexStore())
    service.index_document("kb", IndexDocumentRequest(document_version_id="v1", document_name="Git.md",
        chunks=["这是什么？Git 分支、提交、变基和 git checkout -b 是版本控制的常见操作。", "缓存这些历史数据，可以查看树形历史的过程。这里不涉及所问概念。 "]))
    class MustNotCall:
        def generate(self, *args):
            raise AssertionError("Unrelated evidence must never reach the model")
    service.chat = MustNotCall()
    payload = GenerateRequest(query=query, question=query, knowledge_base_id="kb", document_version_ids=["v1"], answer_mode=mode, evidence_threshold=0)
    assert service.generate(payload).status == "REFUSED"
    assert service.generate(payload).citations == []
    assert not service.search(SearchRequest(query=query, knowledge_base_id="kb", document_version_ids=["v1"])).sufficient


def test_filtering_before_top_k_keeps_relevant_candidate_even_with_colliding_vectors():
    service = RagService(Settings(), MemoryIndexStore())
    query = "B树是什么"
    service.index_document("kb", IndexDocumentRequest(document_version_id="v1", document_name="资料.md",
        chunks=["Git 分支是什么，它的历史是树结构。"] * 8 + ["B树是一种平衡的多路搜索树。每个节点可存放多个关键字。" ]))
    for chunk in service.store.chunks.values():
        chunk.vector = service.embedding.embed(query) if chunk.chunk_no < 8 else [0.] * 384
    result = service.retrieval.search(SearchRequest(query=query, knowledge_base_id="kb", document_version_ids=["v1"], method="VECTOR", top_k=1))
    assert len(result) == 1 and result[0].chunk_no == 8


@pytest.mark.parametrize("question", ["B树呢？", "那么B树是什么？", "B树与B+树有什么区别？", "进程和线程有什么区别？"])
def test_explicit_topic_switch_does_not_inherit_git_history(question):
    assert RagService._rewrite_query(question, [{"role":"user", "content":"Git变基是什么"}]) == question


def test_chained_pronoun_followups_keep_last_explicit_subject():
    history = [{"role":"user", "content":"B树是什么"}, {"role":"user", "content":"它有什么特点？"}]
    query = RagService._rewrite_query("它的复杂度呢？", history)
    assert query.startswith("B树是什么，")
    assert has_relevant_evidence(query, "B树的查找时间复杂度为对数级。")


def test_hybrid_accepts_lexical_evidence_with_zero_hash_similarity():
    service = RagService(Settings(), MemoryIndexStore())
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="B-trees.md",
        chunks=["A B-tree is a balanced search tree with all leaves at the same depth."]))
    for chunk in service.store.chunks.values():
        chunk.vector = [0.] * 384
    request = GenerateRequest(query="B树是什么", question="B树是什么", knowledge_base_id="kb",
                              document_version_ids=["v"], answer_mode="extractive")
    assert service.generate(request).status == "ANSWERED"
    assert service.generate(request.model_copy(update={"method": "VECTOR"})).status == "REFUSED"


def test_lexical_ranking_ignores_question_particles_and_matches_bilingual_term():
    service = RagService(Settings(), MemoryIndexStore())
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="chapter.md",
        chunks=["B树是什么，是一种树。什么是问题，什么是资料，是什么？Git 的分支是什么。",
                "A B-tree is a tree. B-trees have multiple children. B-tree leaves have the same depth."]))
    chunks = list(service.store.chunks.values())
    for chunk in chunks:
        chunk.vector = [0.] * 384
    result = service.retrieval.search(SearchRequest(query="B树是什么", knowledge_base_id="kb", document_version_ids=["v"]))
    assert result[0].chunk_no == 1


def test_question_particles_do_not_change_vector_query_ranking():
    service = RagService(Settings(), MemoryIndexStore())
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="chapter.md",
        chunks=["B树保存多个关键字。", "B树的叶子位于同一层。是这个什么呢？"]))
    def retrieve(query):
        return service.retrieval.search(SearchRequest(query=query, knowledge_base_id="kb", document_version_ids=["v"]))
    assert [(e.chunk_id, e.score, e.vector_score) for e in retrieve("B树是什么")] == [
        (e.chunk_id, e.score, e.vector_score) for e in retrieve("B树")]


@pytest.mark.parametrize("query", ["排序", "排序是什么", "什么是排序？", "二叉树", "TCP"])
def test_concept_lookup_reserves_definition_instead_of_repeated_procedures(query):
    from app.relevance import query_subjects
    topic = query_subjects(query)[0]
    service = RagService(Settings(), MemoryIndexStore())
    definition = topic + "是一种课程中介绍的概念。"
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="overview.md",
        chunks=[(topic + "步骤和具体操作。") * 20] * 8 + [definition]))
    result = service.retrieval.search(SearchRequest(query=query, knowledge_base_id="kb", document_version_ids=["v"], top_k=1))
    assert result[0].content == definition


@pytest.mark.parametrize("query,content", [
    ("排序", "# 排序\n这里只说明 Git。"),
    ("排序", "选择排序是一种具体算法。"),
    ("排序的复杂度是什么", "排序是一种按规则排列数据的操作。"),
    ("TCP和UDP的区别", "TCP是一种协议。"),
])
def test_overview_slot_never_treats_title_or_subtopic_as_definition(query, content):
    assert not is_concept_overview(query, content)


@pytest.mark.parametrize("query", [
    "冒泡算法", "冒泡排序算法", "冒泡排序", "冒泡算法是什么？", "什么是冒泡排序算法？",
])
def test_bubble_algorithm_variants_share_subject_and_body_definition(query):
    definition = "冒泡排序（bubble sort）通过连续地比较与交换相邻元素实现排序。"
    assert query_subjects(query) == ["冒泡排序"]
    assert has_relevant_evidence(query, definition)
    assert is_concept_overview(query, definition)


@pytest.mark.parametrize("query,expected", [
    ("插入排序算法是什么", "插入排序"),
    ("归并排序算法是什么", "归并排序"),
    ("堆排序算法是什么", "堆排序"),
    ("选择算法是什么", "选择算法"),
    ("快速算法是什么", "快速算法"),
    ("事件冒泡是什么", "事件冒泡"),
    ("冒泡算法的时间复杂度是什么", "冒泡排序的时间复杂度"),
])
def test_query_aliases_preserve_facets_and_ambiguous_algorithm_names(query, expected):
    assert query_subjects(query) == [expected]


@pytest.mark.parametrize("query,content", [
    ("冒泡算法", "JavaScript 事件冒泡通过事件处理算法逐级传递给父节点。"),
    ("冒泡排序算法", "排序算法可以使用插入排序或选择排序。"),
    ("冒泡算法", "# 冒泡排序\n这里仅说明 Git 分支。"),
])
def test_bubble_alias_does_not_admit_unrelated_or_title_only_evidence(query, content):
    assert not has_relevant_evidence(query, content)
    assert not is_concept_overview(query, content)


def test_bubble_alias_ranks_definition_first_and_supplies_it_to_generation():
    service = RagService(Settings(), MemoryIndexStore())
    definition = "冒泡排序（bubble sort）通过连续地比较与交换相邻元素实现排序。"
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="排序.md",
        chunks=["插入排序算法在实践中常比冒泡排序算法快。" * 15, definition,
                "JavaScript 事件冒泡是一种向父节点传播事件的机制。算法课程介绍了插入排序。 "]))
    canonical = service.retrieval.search(SearchRequest(query="冒泡排序", knowledge_base_id="kb",
                                                      document_version_ids=["v"], top_k=1))
    calls = []
    class GroundedModel:
        def generate(self, query, evidence, history):
            calls.append((query, evidence, history))
            assert [{k:e[k] for k in ("document_name", "content")} for e in evidence] == [{"document_name": "排序.md", "content": definition}]
            return '{"coverage":"complete","claims":[{"text":"通过比较并交换相邻元素排序。","citations":[1]}],"missing_aspects":[]}'
    service.chat = GroundedModel()
    for query in ["冒泡算法", "冒泡排序算法", "冒泡算法是什么"]:
        actual = service.retrieval.search(SearchRequest(query=query, knowledge_base_id="kb",
                                                        document_version_ids=["v"], top_k=1))
        assert [(e.chunk_id, e.score) for e in actual] == [(e.chunk_id, e.score) for e in canonical]
        result = service.generate(GenerateRequest(query=query, question=query, knowledge_base_id="kb",
            document_version_ids=["v"], top_k=1, answer_mode="generated"))
        assert result.status == "ANSWERED"
        assert result.citations[0].quote == definition
        assert calls[-1][0] == query  # Preserve the user's actual question for generation.
        assert calls[-1][2] == []


def test_bubble_alias_does_not_turn_complexity_question_into_overview():
    assert not is_concept_overview("冒泡算法的时间复杂度是什么", "冒泡排序通过比较与交换实现排序。")


@pytest.mark.parametrize("query", [
    "解释一下B树是二叉树的推广，也可视为2-4树的自然推广；2-4树是令 B=2 时得到的 B树特例",
    "B树是二叉树的推广。 [1][4] 对外部存储器模型而言 B树是高效的。操作时间与节点数成正比。",
    "请解释一下“B树是二叉树的推广”",
    "解释一下B-tree是二叉树的推广",
])
def test_explanatory_statement_retrieves_topic_across_languages(query):
    assert query_subjects(query) == ["b树"]
    assert has_relevant_evidence(query, "A B-tree is a generalization of binary trees. A 2-4 tree is a special case with B=2.")
    assert not has_relevant_evidence(query, "快速排序在大部分输入中操作时间较短，效率较高。")
    assert not has_relevant_evidence(query, "B+ trees store records at the leaves.")


@pytest.mark.parametrize("query,topic", [
    ("解释一下死锁是多个进程互相等待资源造成的一种状态", "死锁"),
    ("解释一下TCP是一种可靠的传输层协议", "tcp"),
    ("解释一下归并排序是一种分治算法", "归并排序"),
])
def test_explanatory_subject_extraction_is_not_btree_specific(query, topic):
    assert query_subjects(query) == [topic]


@pytest.mark.parametrize("query,forbidden_simplification", [
    ("B树是怎么删除节点的？", ["b树"]),
    ("B树是否适用于今天的天气预测？", ["b树"]),
    ("B树是二叉树的推广，并说明TCP三次握手的过程", ["b树"]),
    ("B树是二叉树的推广，量子纠缠是什么？", ["b树"]),
    ("这是一种数据结构", ["这"]),
])
def test_topic_extraction_does_not_drop_explicit_other_tasks_or_question_facets(query, forbidden_simplification):
    assert query_subjects(query) != forbidden_simplification


def test_translated_explanation_reaches_model_with_full_question_and_scoped_evidence():
    import json
    service = RagService(Settings(), MemoryIndexStore())
    definition = "A B-tree is a generalization of binary trees. A 2-4 tree is a special case with B=2."
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="B-Trees.md",
        chunks=[definition, "快速排序的操作时间与输入有关。"] ))
    service.index_document("private", IndexDocumentRequest(document_version_id="private-v", document_name="private.md",
        chunks=[definition] ))
    class ExplainModel:
        def generate(self, question, evidence, history):
            assert question == original
            assert [{k:e[k] for k in ("document_name", "content")} for e in evidence] == [{"document_name":"B-Trees.md", "content":definition}]
            assert history == []
            # Topic evidence is only a candidate; it does not force the model to answer.
            return json.dumps({"coverage":"insufficient","claims":[],"missing_aspects":["原文没有课程考试日期"]})
    service.chat = ExplainModel()
    original = "解释一下B树是二叉树的推广，包含本课程考试日期。"
    response = service.generate(GenerateRequest(question=original, query=original, knowledge_base_id="kb",
        document_version_ids=["v"], answer_mode="generated"))
    assert response.status == "REFUSED"
    assert response.evidence_reason == "MODEL_EVIDENCE_INSUFFICIENT"
    assert response.evidence_count == 1
    assert response.citations == []


@pytest.mark.parametrize("query", [
    "死锁的四个必要条件是什么？", "产生死锁的四个必要条件是什么？",
    "死锁 必要条件", "死锁发生的必要条件包括", "死锁的必要条件有哪些？",
    "请问发生死锁的4个必要条件包括哪些？",
])
def test_condition_question_keeps_topic_and_finds_condition_section(query):
    assert query_subjects(query) == ["死锁"]
    service = RagService(Settings(), MemoryIndexStore())
    conditions = "## 必要条件\n互斥、占有和等待、不可抢占、环路等待。死锁发生时进程相互等待资源。"
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="死锁.md",
        chunks=["死锁检测通过资源分配图发现死锁。" * 12, conditions]))
    results = service.retrieval.search(SearchRequest(query=query, knowledge_base_id="kb",
        document_version_ids=["v"], top_k=1))
    assert results[0].content == conditions
    assert not has_relevant_evidence(query, "# 死锁的必要条件\n本文实际仅介绍 Git 分支。")
    assert not has_relevant_evidence(query, "## 必要条件\n互斥量的加锁和释放需要遵守协议。")


@pytest.mark.parametrize("query", [
    "请比较 B 树和二叉搜索树的查找过程及适用场景。",
    "对比B树与二叉搜索树的查找过程和适用场景",
    "B树和二叉搜索树的区别", "B树与二叉搜索树比较", "B树 二叉搜索树",
])
def test_comparison_query_separates_concepts_from_requested_facets(query):
    assert query_subjects(query) == ["b树", "二叉搜索树"]
    assert has_relevant_evidence(query, "A B-tree is efficient for external memory.")
    assert has_relevant_evidence(query, "二叉搜索树从根节点比较键值并选择左子树或右子树。")
    assert not has_relevant_evidence(query, "B+ trees store data in their leaf nodes.")
    assert not has_relevant_evidence(query, "Git 分支的查找过程及适用场景如下。")
    assert not has_relevant_evidence(query, "# B树与二叉搜索树\nGit 的查找过程及适用场景。")


def test_comparison_balances_both_topics_and_keeps_full_question_for_model():
    import json
    service = RagService(Settings(), MemoryIndexStore())
    query = "请比较 B 树和二叉搜索树的查找过程及适用场景。"
    service.index_document("kb", IndexDocumentRequest(document_version_id="v", document_name="树.md",
        chunks=["二叉搜索树的查找过程及适用场景。" * 20] * 8 + [
            "A B-tree searches nodes using find(x), then follows the chosen child.",
            "A B-tree is used in external memory to reduce block reads."]))
    service.index_document("private", IndexDocumentRequest(document_version_id="private-v", document_name="私有.md",
        chunks=["B树和二叉搜索树的查找过程及适用场景。" * 30]))
    class CompareModel:
        def generate(self, question, evidence, history):
            assert question == query
            assert history == []
            assert 2 <= len(evidence) <= 5
            assert any("B-tree searches" in e["content"] for e in evidence)
            assert any("B-tree is used" in e["content"] for e in evidence)
            assert any("二叉搜索树" in e["content"] for e in evidence)
            assert all(e["document_name"] == "树.md" for e in evidence)
            return json.dumps({"coverage":"partial", "claims":[{"text":"相关段落提供部分对比依据。", "citations":[1]}],
                               "missing_aspects":["仍需评估两种结构的适用条件"]})
    service.chat = CompareModel()
    result = service.generate(GenerateRequest(query=query, question=query, knowledge_base_id="kb",
        document_version_ids=["v"], answer_mode="generated"))
    assert result.evidence_state == "MODEL_REPORTED_PARTIAL"
    assert result.citations
    assert not service.retrieval.search(SearchRequest(query=query, knowledge_base_id="kb", document_version_ids=[]))


@pytest.mark.parametrize("query,topic", [
    ("数据库事务的四个必要条件是什么？", "数据库事务"),
    ("请比较进程和线程的适用场景", "进程"),
])
def test_focus_parsing_is_not_specific_to_the_audit_questions(query, topic):
    assert topic in query_subjects(query)


@pytest.mark.parametrize("query,content", [
    ("死锁预防的必要条件是什么？", "死锁是多个进程相互等待资源的状态。"),
    ("请比较B树和量子纠缠的数学证明", "B树是一种多路平衡搜索树。"),
    ("B树是否适用于今天的天气预测？", "B树是一种多路平衡搜索树。"),
])
def test_focus_parsing_does_not_drop_unknown_qualifiers(query, content):
    assert not has_relevant_evidence(query, content)


def test_comparison_prefers_dedicated_chapters_over_incidental_mentions():
    service = RagService(Settings(), MemoryIndexStore())
    for version, name, chunks in [
        ("linked", "链表.md", ["链表常用于查找场景。B树中父节点引用也有类似用途。" * 20]),
        ("btree", "B-Trees.md", ["A B-tree search starts at the root and determines which child to visit.",
                                 "A B-tree find(x) takes logarithmic time."]),
        ("bst", "二叉搜索树.md", ["二叉搜索树从根节点查找，比较键值并选择左子树或右子树。"]),
        ("title", "B-Trees.md", ["这里只介绍 Git 的查找过程及适用场景。"]),
    ]:
        service.index_document("kb", IndexDocumentRequest(document_version_id=version, document_name=name, chunks=chunks))
    result = service.retrieval.search(SearchRequest(query="请比较B树和二叉搜索树的查找过程及适用场景",
        knowledge_base_id="kb", document_version_ids=["linked", "btree", "bst", "title"], top_k=2))
    assert [e.document_name for e in result] == ["B-Trees.md", "二叉搜索树.md"]
    assert "starts at the root" in result[0].content


def test_comparison_supplies_procedure_and_application_evidence_for_each_side():
    service = RagService(Settings(), MemoryIndexStore())
    for version, name, chunks in [
        ("b", "B-Trees.md", ["A B-tree search starts at the root and determines which child to visit.",
                             "A B-tree is efficient in the external memory model.",
                             "A B-tree supports merge operations." * 20]),
        ("bst", "二叉搜索树.md", ["二叉搜索树从根节点查找，比较键值并选择左子树或右子树。",
                                 "## 二叉搜索树常见应用\n二叉搜索树用于索引和保存有序数据。",
                                 "某些场景下比较二叉搜索树的查找复杂度。" * 20]),
    ]:
        service.index_document("kb", IndexDocumentRequest(document_version_id=version, document_name=name, chunks=chunks))
    result = service.retrieval.search(SearchRequest(query="请比较B树和二叉搜索树的查找过程及适用场景",
        knowledge_base_id="kb", document_version_ids=["b", "bst"], top_k=4))
    assert [(e.document_version_id, e.chunk_no) for e in result] == [("b", 0), ("bst", 0), ("b", 1), ("bst", 1)]
