import httpx
import pytest
import logging
from app.providers import ChatProvider, ChatProviderError


@pytest.mark.parametrize('kind,reason', [('http', 'upstream_http_status'), ('network', 'ConnectError'),
                                       ('empty', 'empty_content'), ('structure', 'invalid_response_structure')])
def test_failure_diagnostics_are_correlated_and_never_log_secrets(monkeypatch, caplog, kind, reason):
    from app.diagnostics import request_trace_id
    def post(url, **kwargs):
        if kind == 'network':
            raise httpx.ConnectError('secret-network-error-text')
        payload = {'choices': [{'message': {'content': ''}}]} if kind == 'empty' else {'secret': 'secret-upstream-body'}
        return httpx.Response(500 if kind == 'http' else 200, request=httpx.Request('POST', url), json=payload)
    monkeypatch.setattr(httpx, 'post', post)
    token = request_trace_id.set('test-trace-1234')
    try:
        with caplog.at_level(logging.WARNING), pytest.raises(ChatProviderError):
            ChatProvider('openai-compatible', 'https://model.example.test', 'secret-api-key', 'model').generate_general('secret-user-question')
    finally:
        request_trace_id.reset(token)
    assert 'trace=test-trace-1234' in caplog.text
    assert 'reason=' + reason in caplog.text
    assert 'secret-' not in caplog.text
    if kind == 'http':
        assert 'upstream_status=500' in caplog.text


def test_external_provider_receives_retrieved_evidence(monkeypatch):
    captured = {}
    def post(url, **kwargs):
        captured.update({"url": url, **kwargs})
        return httpx.Response(200, request=httpx.Request("POST", url),
                              json={"choices": [{"message": {"content": "依据原文的回答 [1]"}}]})
    monkeypatch.setattr(httpx, "post", post)
    provider = ChatProvider("openai-compatible", "https://model.example.test/v1", "test-only-key", "test-model")
    answer = provider.generate("等价类是什么？", [{"document_name": "lecture.txt", "content": "等价类划分的原文证据"}], [])
    assert answer.endswith("[1]")
    assert captured["url"].endswith("/v1/chat/completions")
    assert "等价类划分的原文证据" in captured["json"]["messages"][0]["content"]
    assert "只根据提供的原文回答" in captured["json"]["messages"][0]["content"]
    assert captured["json"]["messages"][-1]["content"] == "等价类是什么？"


@pytest.mark.parametrize("status,code", [(401, "CHAT_MODEL_AUTH_FAILED"), (429, "CHAT_MODEL_RATE_LIMITED"), (500, "CHAT_MODEL_ERROR"),
    (400, "CHAT_MODEL_INVALID_REQUEST"), (422, "CHAT_MODEL_INVALID_REQUEST"), (402, "CHAT_MODEL_BALANCE_INSUFFICIENT")])
def test_provider_errors_have_safe_codes(monkeypatch, status, code):
    monkeypatch.setattr(httpx, "post", lambda url, **kwargs: httpx.Response(status,
        request=httpx.Request("POST", url), json={"secret": "upstream-sensitive-body"}))
    with pytest.raises(ChatProviderError) as error:
        ChatProvider("openai-compatible", "https://model.example.test/v1", "test-secret", "test").generate("q", [], [])
    assert error.value.code == code
    assert "sensitive" not in str(error.value)


def test_provider_timeout_is_explicit(monkeypatch):
    def timeout(*args, **kwargs):
        raise httpx.ReadTimeout("upstream timeout")
    monkeypatch.setattr(httpx, "post", timeout)
    with pytest.raises(ChatProviderError) as error:
        ChatProvider("openai-compatible", "https://model.example.test/v1", "test-secret", "test").generate("q", [], [])
    assert error.value.code == "CHAT_MODEL_TIMEOUT"


def test_previous_questions_are_not_sent_as_unanswered_requests(monkeypatch):
    captured = {}
    def post(url, **kwargs):
        captured.update(kwargs)
        return httpx.Response(200, request=httpx.Request("POST", url),
            json={"choices": [{"message": {"content": "test response"}}]})
    monkeypatch.setattr(httpx, "post", post)
    ChatProvider("openai-compatible", "https://model.example.test", "test-only", "test").generate(
        "current topic", [{"document_name": "source", "content": "current evidence"}],
        [{"role": "user", "content": "previous-unrelated-question"}])
    assert len(captured["json"]["messages"]) == 2
    assert all("previous-unrelated-question" not in m["content"] for m in captured["json"]["messages"])


@pytest.mark.parametrize("general", [False, True])
def test_deepseek_uses_official_non_thinking_and_grounded_json(monkeypatch, general):
    captured = {}
    def post(url, **kwargs):
        captured.update({"url": url, **kwargs})
        return httpx.Response(200, request=httpx.Request("POST", url),
            json={"choices": [{"finish_reason": "stop", "message": {"content": "test response"}}]})
    monkeypatch.setattr(httpx, "post", post)
    provider = ChatProvider("openai-compatible", "https://api.deepseek.com", "local-test-only", "deepseek-flash")
    if general:
        provider.generate_general("q")
    else:
        provider.generate("q", [{"document_name": "source", "content": "evidence"}], [])
    assert captured["url"] == "https://api.deepseek.com/chat/completions"
    assert captured["json"]["model"] == "deepseek-flash"
    assert captured["json"]["thinking"] == {"type": "disabled"}
    assert captured["json"]["max_tokens"] == 4096
    assert ("response_format" in captured["json"]) is not general


def test_provider_specific_parameters_never_go_to_lookalike_host(monkeypatch):
    captured = {}
    def post(url, **kwargs):
        captured.update(kwargs)
        return httpx.Response(200, request=httpx.Request("POST", url),
            json={"choices": [{"message": {"content": "test response"}}]})
    monkeypatch.setattr(httpx, "post", post)
    ChatProvider("openai-compatible", "https://api.deepseek.com.example.test", "test-only", "test").generate("q", [], [])
    assert "thinking" not in captured["json"]
    assert "response_format" not in captured["json"]


@pytest.mark.parametrize("reason", ["length", "content_filter", "tool_calls"])
def test_truncated_or_non_answer_completion_is_rejected(monkeypatch, reason):
    monkeypatch.setattr(httpx, "post", lambda url, **kwargs: httpx.Response(200,
        request=httpx.Request("POST", url), json={"choices": [{"finish_reason": reason, "message": {"content": "incomplete"}}]}))
    with pytest.raises(ChatProviderError) as error:
        ChatProvider("openai-compatible", "https://api.deepseek.com", "test-only", "deepseek-flash").generate("q", [], [])
    assert error.value.code == "CHAT_MODEL_INVALID_RESPONSE"


def test_model_sees_numbered_continuous_passages_without_offset_arithmetic(monkeypatch):
    captured = {}
    def post(url, **kwargs):
        captured.update(kwargs)
        return httpx.Response(200, request=httpx.Request("POST", url),
            json={"choices": [{"message": {"content": "test response"}}]})
    monkeypatch.setattr(httpx, "post", post)
    overlap = "所有节点的值小于根节点的值，右子树中所有节点的值大于根节点。"
    evidence = [
        {"document_name":"树.md", "content":"左子树中" + overlap + "\n二叉搜索树从根查找。"},
        {"document_name":"树.md", "content":"另一版本原文。"},
    ]
    ChatProvider("openai-compatible", "https://model.example.test", "test-only", "test").generate("比较查找过程", evidence, [])
    prompt = captured['json']['messages'][0]['content']
    assert "左子树中" + overlap + "\n二叉搜索树从根查找。" in prompt
    assert prompt.count(overlap) == 1
    assert "[2] 文档" in prompt and "[1] 文档" in prompt and "[3] 文档" not in prompt
    assert "另一版本原文。" in prompt


def test_comparison_coverage_checks_only_requested_facets_without_asserting_support(monkeypatch):
    captured = {}
    def post(url, **kwargs):
        captured.update(kwargs)
        return httpx.Response(200, request=httpx.Request("POST", url),
            json={"choices": [{"message": {"content": "test response"}}]})
    monkeypatch.setattr(httpx, "post", post)
    question = "请比较B树和二叉搜索树的查找过程及适用场景"
    ChatProvider("openai-compatible", "https://model.example.test", "test-only", "test").generate(
        question, [{"document_name":"source", "content":"Partial source only"}], [])
    prompt = captured['json']['messages'][0]['content']
    assert all(f"{subject}：{facet}" in prompt for subject in ['b树', '二叉搜索树'] for facet in ['查找过程', '适用场景'])
    assert '不是已获支持的结论' in prompt
    assert '不要求原文已写好两者的直接对比' in prompt
    assert '任一明确要求确实未被覆盖时仍须 partial' in prompt
    assert captured['json']['messages'][-1]['content'] == question
