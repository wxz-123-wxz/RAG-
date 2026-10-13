"""Coverage and explicit fallback tests using a local HTTP test double, not a real LLM."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from app.config import Settings
from app.providers import ChatProviderError
from app.retrieval import MemoryIndexStore
from app.schemas import GenerateRequest, IndexDocumentRequest
from app.service import RagService


def coverage_draft():
    return json.dumps({'coverage':'partial', 'claims':[{'text':'来源提供查找过程。', 'citations':[1]}],
                       'missing_aspects':['适用场景']})


def coverage_audit(supported=True):
    return {'aspects':[
        {'id':1, 'status':'supported', 'text':'从根节点查找。',
         'evidence':[{'source':1,'quote':'B树从根节点开始查找，适用于外部存储模型。'}]},
        {'id':2, 'status':'supported' if supported else 'insufficient',
         'text':'适用于外部存储模型。' if supported else '',
         'evidence':[{'source':1,'quote':'适用于外部存储模型。'}] if supported else []},
        {'id':3, 'status':'supported', 'text':'二叉搜索树从根节点比较键值。',
         'evidence':[{'source':1,'quote':'二叉搜索树从根节点比较键值，可以维护有序数据。'}]},
        {'id':4, 'status':'supported', 'text':'用于维护有序数据。',
         'evidence':[{'source':1,'quote':'可以维护有序数据。'}]},
    ]}


@pytest.fixture
def review_service(monkeypatch):
    from app.providers import ChatProvider
    service = RagService(Settings(chat_provider='openai-compatible', chat_base_url='https://model.example.test',
        chat_model='test-double', chat_api_key='test-only', chat_timeout_seconds=25), MemoryIndexStore())
    source = 'B树从根节点开始查找，适用于外部存储模型。二叉搜索树从根节点比较键值，可以维护有序数据。'
    service.index_document('kb', IndexDocumentRequest(document_version_id='v', document_name='树.md', chunks=[source]))
    payload = GenerateRequest(question='请比较B树和二叉搜索树的查找过程及适用场景', query='ignored',
        knowledge_base_id='kb', document_version_ids=['v'], answer_mode='generated')
    state = {'calls':[], 'review':json.dumps(coverage_audit()), 'initial':coverage_draft()}
    def mock_request(self, messages, *, grounded=False, timeout_seconds=None):
        state['calls'].append({'messages':messages,'timeout':timeout_seconds})
        if len(state['calls']) == 1:
            return state['initial']
        if isinstance(state['review'], Exception):
            raise state['review']
        return state['review']
    monkeypatch.setattr(ChatProvider, '_request', mock_request)
    return service, payload, state, source


@pytest.mark.parametrize('supported', [True, False])
def test_quote_checked_review_corrects_false_gap_and_preserves_real_gap(review_service, supported):
    service, payload, state, source = review_service
    state['review'] = json.dumps(coverage_audit(supported))
    result = service.generate(payload)
    assert result.evidence_state == ('MODEL_REPORTED_COMPLETE' if supported else 'MODEL_REPORTED_PARTIAL')
    assert len(state['calls']) == 2
    assert 0 < state['calls'][1]['timeout'] <= service.settings.chat_timeout_seconds
    assert result.citations[0].quote == source
    assert ('资料未覆盖的部分' in result.answer) is not supported
    if not supported:
        assert 'b树的适用场景' in result.answer


@pytest.mark.parametrize('defect', ['fabricated_quote','wrong_source','missing_id','duplicate_id','extra_id','prose_citation','malformed','timeout'])
def test_invalid_or_failed_review_preserves_valid_conservative_draft(review_service, defect):
    service, payload, state, _ = review_service
    data = coverage_audit()
    if defect == 'fabricated_quote':
        data['aspects'][1]['evidence'][0]['quote'] = '这段杜撰的结论不在原文里面。'
    elif defect == 'wrong_source':
        data['aspects'][1]['evidence'][0]['source'] = 99
    elif defect == 'missing_id':
        data['aspects'].pop()
    elif defect == 'duplicate_id':
        data['aspects'][1]['id'] = 1
    elif defect == 'extra_id':
        data['aspects'][-1]['id'] = 5
    elif defect == 'prose_citation':
        data['aspects'][0]['text'] = 'Untrusted numbered assertion [99]'
    state['review'] = ('not json' if defect == 'malformed' else
                       ChatProviderError('timeout','CHAT_MODEL_TIMEOUT') if defect == 'timeout' else json.dumps(data))
    result = service.generate(payload)
    assert result.evidence_state == 'MODEL_REPORTED_PARTIAL'
    assert '来源提供查找过程。' in result.answer
    assert len(state['calls']) == 2


@pytest.mark.parametrize('kind', ['complete','general','unknown_facet','no_evidence','invalid_initial','expired_budget'])
def test_review_is_bounded_and_never_changes_general_or_missing_evidence_path(review_service, monkeypatch, kind):
    import app.service as service_module
    service, payload, state, _ = review_service
    if kind == 'complete':
        state['initial'] = json.dumps({'coverage':'complete','claims':[{'text':'已有依据。','citations':[1]}],'missing_aspects':[]})
    elif kind == 'general':
        payload.answer_mode = 'general'
        state['initial'] = 'General answer array[0]'
    elif kind == 'unknown_facet':
        payload.question = 'B树和二叉搜索树的考试日期'
    elif kind == 'no_evidence':
        payload.document_version_ids = []
    elif kind == 'invalid_initial':
        state['initial'] = 'invalid initial answer'
    elif kind == 'expired_budget':
        readings = iter([0, 26, 26])
        monkeypatch.setattr(service_module.time, 'perf_counter', lambda: next(readings, 26))
    if kind == 'invalid_initial':
        with pytest.raises(ChatProviderError):
            service.generate(payload)
    else:
        result = service.generate(payload)
        if kind == 'general':
            assert result.citations == [] and result.answer_origin == 'GENERAL'
        if kind == 'no_evidence':
            assert result.status == 'REFUSED'
    assert len(state['calls']) <= 1


def test_review_can_retain_or_correct_structured_insufficient_result(review_service):
    service, payload, state, _ = review_service
    state['initial'] = json.dumps({'coverage':'insufficient','claims':[],'missing_aspects':['缺少依据']})
    result = service.generate(payload)
    assert result.evidence_state == 'MODEL_REPORTED_COMPLETE'
    state['calls'].clear()
    state['review'] = json.dumps({'aspects':[{'id':n,'status':'insufficient','text':'','evidence':[]} for n in range(1,5)]})
    result = service.generate(payload)
    assert result.status == 'REFUSED' and result.citations == []


def test_review_quote_whitespace_is_tolerant_but_cannot_change_a_negation():
    evidence = [{'content':'这项操作在没有权限时\n 不能执行。'}]
    raw = {'aspects':[{'id':1,'status':'supported','text':'无权限时不能执行。',
                       'evidence':[{'source':1,'quote':'这项操作在没有权限时 不能执行。'}]}]}
    assert RagService._validate_coverage_review(json.dumps(raw), ['权限条件'], evidence).coverage == 'complete'
    raw['aspects'][0]['evidence'][0]['quote'] = '这项操作在没有权限时 能执行。'
    with pytest.raises(ChatProviderError):
        RagService._validate_coverage_review(json.dumps(raw), ['权限条件'], evidence)


@pytest.fixture
def local_model():
    state = {"calls": [], "answer": ""}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            assert self.path == "/v1/chat/completions"
            assert self.headers["Authorization"] == "Bearer local-test-only"
            state["calls"].append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
            payload = json.dumps({"choices": [{"message": {"content": state["answer"]}}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    settings = Settings(chat_provider="openai-compatible", answer_mode="extractive",
                        chat_base_url=f"http://127.0.0.1:{server.server_port}/v1",
                        chat_api_key="local-test-only", chat_model="test-double")
    service = RagService(settings, MemoryIndexStore())
    service.index_document("kb", IndexDocumentRequest(document_version_id="v1", document_name="source.txt",
        chunks=["alpha software testing source evidence"]))
    try:
        yield service, state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def request(mode="generated", question="alpha software testing"):
    return GenerateRequest(question=question, query=question, knowledge_base_id="kb",
                           document_version_ids=["v1"], answer_mode=mode)


@pytest.mark.parametrize("coverage", ["complete", "partial"])
def test_real_local_http_transports_evidence_and_cited_coverage(local_model, coverage):
    service, state = local_model
    state["answer"] = json.dumps({"coverage": coverage,
        "claims": [{"text": "Supported testing point", "citations": [1]}],
        "missing_aspects": ["Missing comparison"] if coverage == "partial" else []})
    result = service.generate(request())
    assert result.answer_origin == "KNOWLEDGE_BASE"
    assert result.evidence_state == "MODEL_REPORTED_" + coverage.upper()
    assert result.citations[0].quote == "alpha software testing source evidence"
    assert "[1]" in result.answer
    assert "source evidence" in state["calls"][0]["messages"][0]["content"]
    assert ("Missing comparison" in result.answer) == (coverage == "partial")


def test_retrieval_miss_never_calls_even_enabled_model(local_model):
    service, state = local_model
    result = service.generate(request(question="xylophonequasarzz"))
    assert result.status == "REFUSED"
    assert result.answer_origin == "NONE"
    assert state["calls"] == []


def test_model_reported_insufficient_evidence_refuses(local_model):
    service, state = local_model
    state["answer"] = json.dumps({"coverage": "insufficient", "claims": [], "missing_aspects": ["Not covered"]})
    result = service.generate(request())
    assert result.status == "REFUSED" and result.citations == []
    assert result.model == "test-double" and result.evidence_count > 0


def test_explicit_general_never_sends_documents_or_history(local_model):
    service, state = local_model
    state["answer"] = "通用说明，需要自行核验。"
    payload = request(mode="general", question="设计模式是什么？")
    payload.history = [{"role": "user", "content": "private-history-marker"}]
    result = service.generate(payload)
    sent = json.dumps(state["calls"], ensure_ascii=False)
    assert "private-history-marker" not in sent
    assert "source evidence" not in sent
    assert result.answer_origin == "GENERAL"
    assert result.evidence_state == "NOT_APPLICABLE"
    assert result.citations == [] and result.evidence_count == 0
    assert result.answer.startswith("通用知识回答（非知识库来源，未联网核验）")


@pytest.mark.parametrize("text", [
    "读取 arr[0]，再与 arr[1] 比较。",
    "[1] 比较相邻元素。\n[2] 必要时交换。",
    "Model-authored reference [1] is not a verified knowledge-base source.",
])
def test_general_numbers_never_require_or_create_verified_kb_citations(local_model, text):
    service, state = local_model
    state["answer"] = text
    result = service.generate(request(mode="general"))
    assert result.status == "ANSWERED"
    assert text in result.answer
    assert result.answer.startswith("通用知识回答（非知识库来源，未联网核验）")
    assert result.answer_origin == "GENERAL"
    assert result.evidence_state == "NOT_APPLICABLE"
    assert result.citations == [] and result.evidence_count == 0
    sent = json.dumps(state["calls"], ensure_ascii=False)
    assert "source evidence" not in sent


@pytest.mark.parametrize("code", [
    "```c\nint n = sizeof(arr) / sizeof(arr[0]);\n```",
    "读取 `arr[0]`，初始化为 `[1, 2]`。",
    "~~~python\nfirst = values[0]\n~~~",
    "````text\n```c\narray[0]\n```\n````",
    "代码 ``array[0] + `literal` `` 保留原样。",
])
@pytest.mark.parametrize("mode", ["general", "generated"])
def test_program_indices_in_markdown_code_are_not_citation_markers(local_model, code, mode):
    service, state = local_model
    state["answer"] = code if mode == "general" else json.dumps({"coverage": "complete",
        "claims": [{"text": code, "citations": [1]}], "missing_aspects": []})
    result = service.generate(request(mode=mode))
    assert result.status == "ANSWERED"
    assert code in result.answer  # Do not modify source code or remove brackets.
    if mode == "general":
        assert result.answer_origin == "GENERAL"
        assert result.citations == []
    else:
        assert len(result.citations) == 1


@pytest.mark.parametrize("text", [
    "虚构来源 [1]\n```c\na[0];\n```",
    "```c\na[0];\n```\n根据课程资料 [1]",
    "代码 `a[0]`，引用 [1]",
    "```c\na[0];\n未闭合代码不能隐藏引用 [1]",
    "````c\na[0];\n```\n短围栏不能提前结束 [1]",
])
def test_code_does_not_disable_grounded_prose_citation_validation(local_model, text):
    service, state = local_model
    state["answer"] = json.dumps({"coverage": "complete",
        "claims": [{"text": text, "citations": [1]}], "missing_aspects": []})
    with pytest.raises(ChatProviderError) as error:
        service.generate(request())
    assert error.value.code == "CHAT_MODEL_INVALID_CITATION"


def test_valid_code_does_not_allow_out_of_range_structured_citation(local_model):
    service, state = local_model
    state["answer"] = json.dumps({"coverage": "complete",
        "claims": [{"text": "```c\na[0];\n```", "citations": [99]}], "missing_aspects": []})
    with pytest.raises(ChatProviderError) as error:
        service.generate(request())
    assert error.value.code == "CHAT_MODEL_INVALID_CITATION"


@pytest.mark.parametrize('text,expected', [
    ('键放在 u.keys[0]，随后读取 u.keys[1]。', '键放在 `u.keys[0]`，随后读取 `u.keys[1]`。'),
    ('已标记 `u.keys[0]`，另一个 u.keys[1]。', '已标记 `u.keys[0]`，另一个 `u.keys[1]`。'),
    ('```text\nu.keys[0]\n```\n读取 u.keys[1]。', '```text\nu.keys[0]\n```\n读取 `u.keys[1]`。'),
])
def test_source_member_array_indices_are_formatted_not_rejected(text, expected):
    source = [{'content': '节点的键为 u.keys[0] 和 u.keys[1]。'}]
    raw = json.dumps({'coverage': 'complete', 'claims': [{'text': text, 'citations': [1]}], 'missing_aspects': []})
    result = RagService._validate_grounded(raw, 1, source)
    assert result.claims[0].text == expected
    assert result.claims[0].citations == [1]


@pytest.mark.parametrize('text,citations', [
    ('u.keys[0]，资料 [2]', [1]),
    ('u.keys[0]，资料[1]', [1]),
    ('u.keys[0]', [99]),
    ('other.keys[0]', [1]),
    ('source[1]', [1]),
])
def test_source_code_formatting_cannot_hide_invalid_citations(text, citations):
    raw = json.dumps({'coverage': 'complete', 'claims': [{'text': text, 'citations': citations}], 'missing_aspects': []})
    with pytest.raises(ChatProviderError) as error:
        RagService._validate_grounded(raw, 1, [{'content': '节点的键为 u.keys[0]。'}])
    assert error.value.code == 'CHAT_MODEL_INVALID_CITATION'


def test_ods_latex_member_array_display_wrappers_preserve_index_and_source():
    source = [{'content': r'keys: $ \ensuremath{\mathtt{u.keys}}[0],\ldots,\ensuremath{\mathtt{u.keys}}[2B-1]$'}]
    original = source[0]['content']
    assert RagService._format_source_array_accesses('读取 u.keys[0]。', source) == '读取 `u.keys[0]`。'
    assert RagService._format_source_array_accesses('读取 u.keys[99]。', source) == '读取 u.keys[99]。'
    assert source[0]['content'] == original


@pytest.mark.parametrize("output", [
    {"coverage": "complete", "claims": [], "missing_aspects": []},
    {"coverage": "partial", "claims": [{"text": "point", "citations": [1]}], "missing_aspects": []},
    {"coverage": "complete", "claims": [{"text": "point", "citations": []}], "missing_aspects": []},
    {"coverage": "insufficient", "claims": [{"text": "unsupported", "citations": [1]}], "missing_aspects": []},
])
def test_inconsistent_coverage_fails_closed(local_model, output):
    service, state = local_model
    state["answer"] = json.dumps(output)
    with pytest.raises(ChatProviderError) as error:
        service.generate(request())
    assert error.value.code == "CHAT_MODEL_INVALID_RESPONSE"
