"""Real local HTTP/MySQL/index smoke checks. No external model, no invented metrics."""
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
import httpx

ROOT = Path(__file__).resolve().parents[1]
CONFIG = dict(line.split("=", 1) for line in (ROOT / ".runtime/local.properties").read_text(encoding="utf-8").splitlines()
              if line and not line.startswith("#"))
API = "http://127.0.0.1:8080/api/v1"
RAG = "http://127.0.0.1:8000"
REPORT = {"executed_at": datetime.now(timezone.utc).isoformat(), "checks": [],
          "chat_provider": CONFIG.get("RAG_CHAT_PROVIDER", "disabled"),
          "scope": "Local service integration only; not an accuracy or performance experiment."}


def check(name, condition):
    assert condition, name
    REPORT["checks"].append(name)
    print("PASS", name, flush=True)


def request(client, method, path, expected=200, **kwargs):
    response = client.request(method, API + path, **kwargs)
    assert response.status_code == expected, f"{method} {path}: {response.status_code} {response.text[:700]}"
    return response.json().get("data") if response.content else None


def sql(query):
    env = os.environ | {"MYSQL_PWD": CONFIG["APP_DB_PASSWORD"]}
    result = subprocess.run(["mysql", "--no-defaults", "--default-character-set=utf8mb4", "--host=127.0.0.1",
        "--port=3307", "--user=rag_qa", "--database=rag_qa", "--batch", "--skip-column-names"],
        input=query, text=True, encoding="utf-8", capture_output=True, env=env, check=True)
    return result.stdout.strip()


def verify_answer_modes():
    """Reuse the completed smoke KB; never call an external model in this check."""
    output = ROOT / "data/evaluation/local-smoke-latest.json"
    prior = json.loads(output.read_text(encoding="utf-8"))
    check_names = []
    def verified(name, condition):
        assert condition, name
        check_names.append(name)
        print("PASS", name, flush=True)
    with httpx.Client(timeout=45, trust_env=False) as client:
        auth = request(client, "POST", "/auth/login", json={"username": "admin", "password": CONFIG["LOCAL_ADMIN_PASSWORD"]})
        client.headers["Authorization"] = "Bearer " + auth["accessToken"]
        capabilities = request(client, "GET", "/system/capabilities")
        verified("capabilities_reveal_no_credentials", not any("key" in key.lower() or "token" in key.lower() for key in capabilities))
        conversation = request(client, "POST", "/conversations", 201, json={
            "knowledgeBaseId": prior["knowledge_base_id"], "title": "回答模式与无依据拒答联调"})
        ask = "/conversations/" + conversation["id"] + "/messages"
        try:
            result = request(client, "POST", ask, json={"content": "什么是等价类划分？", "answerMode": "extractive"})
            answer = result["assistantMessage"]
            verified("explicit_extractive_answer_and_citations", answer["status"] == "ANSWERED" and answer["model"] == "extractive-no-llm" and len(answer["citations"]) > 0)
            verified("answer_model_persisted_in_mysql", sql("SELECT model_name FROM messages WHERE id=" + answer["id"] + ";") == "extractive-no-llm")
            regenerated = request(client, "POST", "/messages/" + result["userMessageId"] + "/regenerations", json={"answerMode": "extractive"})
            verified("regeneration_preserves_explicit_mode", regenerated["assistantMessage"]["model"] == "extractive-no-llm")
            if not capabilities["chatConfigured"]:
                before = request(client, "GET", "/conversations/" + conversation["id"])["messages"]
                failed = client.post(API + ask, json={"content": "什么是等价类划分？", "answerMode": "generated"})
                verified("generated_without_configuration_returns_error", failed.status_code == 503 and failed.json()["error"]["code"] == "CHAT_MODEL_NOT_CONFIGURED")
                after = request(client, "GET", "/conversations/" + conversation["id"])["messages"]
                verified("failed_generation_rolls_back_messages", len(before) == len(after))
            for mode in ("extractive", "generated"):
                result = request(client, "POST", ask, json={"content": "xylophonequasarzz", "answerMode": mode})
                answer = result["assistantMessage"]
                verified(mode + "_refuses_without_evidence", answer["status"] == "REFUSED" and answer["citations"] == [] and answer["model"] == "disabled")
                verified(mode + "_refusal_has_no_saved_citations", request(client, "GET", "/messages/" + answer["id"] + "/citations") == [])
            invalid = client.post(API + ask, json={"content": "等价类", "answerMode": "unrestricted"})
            verified("invalid_mode_rejected", invalid.status_code == 400)
        finally:
            request(client, "DELETE", "/conversations/" + conversation["id"], 204)
    prior["mode_verification"] = {"executed_at": datetime.now(timezone.utc).isoformat(),
        "checks": check_names, "passed": len(check_names), "external_model_called": False}
    output.write_text(json.dumps(prior, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"mode_checks_passed": len(check_names), "external_model_called": False}, ensure_ascii=False))


def main():
    with httpx.Client(timeout=45, trust_env=False) as client:
        check("backend_health", client.get("http://127.0.0.1:8080/health/live").status_code == 200)
        check("fastapi_health", client.get(RAG + "/health/ready").json()["status"] == "UP")
        check("frontend_http", client.get("http://127.0.0.1:5173").status_code == 200)
        check("unauthenticated_access_rejected", client.get(API + "/knowledge-bases").status_code == 401)
        auth = request(client, "POST", "/auth/login", json={"username": "admin", "password": CONFIG["LOCAL_ADMIN_PASSWORD"]})
        client.headers["Authorization"] = "Bearer " + auth["accessToken"]
        check("login_and_exact_string_id", isinstance(auth["user"]["id"], str))
        stamp = datetime.now().strftime("%Y%m%d%H%M%S")
        kb = request(client, "POST", "/knowledge-bases", 201, json={"code": "smoke-" + stamp,
            "name": "软件测试联调 " + stamp, "courseName": "软件测试", "visibility": "PUBLIC",
            "description": "自动化联调样本；不是正式课程题集或效果实验。"})
        kb_id = kb["id"]
        REPORT["knowledge_base_id"] = kb_id
        check("knowledge_base_created_with_exact_id", isinstance(kb_id, str))
        base = "/knowledge-bases/" + kb_id
        content = "等价类划分是一种黑盒测试用例设计方法。把输入域划分为有效等价类和无效等价类，再从每一类选取代表值进行测试。"
        versions = []
        for filename, text in [("smoke-testing.txt", content), ("smoke-boundary.md", "# 边界值分析\n边界值分析关注输入范围的边界，选取边界上以及边界两侧的值设计测试用例。")]:
            uploaded = request(client, "POST", base + "/documents", 202,
                               files={"file": (filename, text.encode("utf-8"), "text/plain")})
            for _ in range(60):
                task = request(client, "GET", "/ingestion-tasks/" + uploaded["taskId"])
                if task["status"] in {"READY", "FAILED"}: break
                time.sleep(.25)
            check(filename + "_indexed", task["status"] == "READY")
            request(client, "POST", base + "/documents/" + uploaded["documentId"] + "/publish")
            versions.append(uploaded["versionId"])
        check("documents_published", all(row["status"] == "PUBLISHED" for row in request(client, "GET", base + "/documents")))
        duplicate = client.post(API + base + "/documents", files={"file": ("duplicate.txt", content.encode("utf-8"), "text/plain")})
        check("duplicate_rejected", duplicate.status_code == 409)
        request(client, "POST", base + "/publish")
        check("knowledge_base_published", request(client, "GET", base)["status"] == "PUBLISHED")
        headers = {"Authorization": "Bearer " + CONFIG["RAG_SERVICE_TOKEN"]}
        payload = {"query": "什么是等价类划分？", "knowledge_base_id": kb_id, "document_version_ids": versions, "method": "HYBRID"}
        check("rag_requires_internal_token", httpx.post(RAG + "/internal/v1/retrieval/search", json=payload, trust_env=False).status_code == 401)
        retrieved = client.post(RAG + "/internal/v1/retrieval/search", json=payload, headers=headers).json()
        check("real_hybrid_retrieval", retrieved["sufficient"] and content in [entry["content"] for entry in retrieved["evidence"]])
        index = json.loads((ROOT / ".runtime/rag-index.json").read_text(encoding="utf-8"))
        chunks = [entry for entry in index if entry["document_version_id"] in versions]
        count = int(sql("SELECT COUNT(*) FROM document_chunks WHERE document_version_id IN (" + ",".join(versions) + ");"))
        check("mysql_and_index_chunk_counts_match", count == len(chunks) == 2)
        for entry in chunks:
            stored = sql(f"SELECT content FROM document_chunks WHERE document_version_id={entry['document_version_id']} AND chunk_no={entry['chunk_no']};")
            check("chunk_coordinate_and_content_" + entry["document_version_id"], stored == entry["content"].replace("\n", "\\n"))
        convo = request(client, "POST", "/conversations", 201, json={"knowledgeBaseId": kb_id, "title": "基础链路联调"})
        ask = "/conversations/" + convo["id"] + "/messages"
        response = client.post(API + ask, json={"content": "什么是等价类划分？", "answerMode": "extractive"})
        check("answer_available", response.status_code == 200)
        known_answer = response.json()["data"]["assistantMessage"]
        check("extractive_mode_explicitly_labelled", "未调用生成模型" in known_answer["content"])
        citations = request(client, "GET", "/messages/" + known_answer["id"] + "/citations")
        check("answer_has_actual_citations", len(citations) > 0)
        for citation in citations:
            original = sql(f"SELECT content FROM document_chunks WHERE id={citation['chunkId']} AND document_version_id={citation['documentVersionId']} AND chunk_no={citation['chunkNo']};")
            check("citation_matches_mysql_" + str(citation["number"]), original == citation["quote"].replace("\n", "\\n"))
        request(client, "PUT", "/messages/" + known_answer["id"] + "/feedback", 204, json={"helpful": True, "comment": "联调：原文与引用回查一致"})
        check("cited_answer_feedback_saved", request(client, "GET", "/messages/" + known_answer["id"] + "/feedback")["helpful"] is True)
        REPORT["answer_message_id"] = known_answer["id"]
        REPORT["citations"] = citations
        REPORT["real_generated_answer_and_citations"] = "Extractive citations verified; no Chat Model used"
        answer = request(client, "POST", ask, json={"content": "xylophonequasarzz", "answerMode": "extractive"})
        assistant = answer["assistantMessage"]
        check("unrelated_question_refused", assistant["status"] == "REFUSED")
        check("refusal_has_no_fabricated_citations", request(client, "GET", "/messages/" + assistant["id"] + "/citations") == [])
        request(client, "PUT", "/messages/" + assistant["id"] + "/feedback", 204, json={"helpful": True, "comment": "联调：无依据拒答符合预期"})
        check("feedback_saved", request(client, "GET", "/messages/" + assistant["id"] + "/feedback")["helpful"] is True)
        for code, question, refuse in [("SMOKE-KNOWN", "什么是等价类划分？", False), ("SMOKE-OUTSIDE", "xylophonequasarzz", True)]:
            request(client, "POST", base + "/evaluation-questions", 201, json={"questionCode": code, "question": question,
                "questionType": "CONCEPT" if not refuse else "OUT_OF_SCOPE", "shouldRefuse": refuse,
                "referenceDocument": "smoke-testing.txt" if not refuse else None, "evidenceText": content if not refuse else None})
        run = request(client, "POST", base + "/evaluation-runs", 202, json={"name": "真实服务联调-非效果实验",
            "retrievalMethod": "HYBRID", "configSnapshotJson": json.dumps({"purpose": "integration-smoke", "chatProvider": CONFIG.get("RAG_CHAT_PROVIDER", "disabled"), "embedding": "hashing-baseline"})})
        results = request(client, "GET", base + "/evaluation-runs/" + run["id"] + "/results")
        check("evaluation_two_rows_persisted", len(results) == 2)
        check("evaluation_extracts_known_evidence", all(row["status"] == "COMPLETED" for row in results))
        check("evaluation_records_valid_refusal", any(row["status"] == "COMPLETED" and row["refusalCorrect"] for row in results))
        REPORT["evaluation_run_id"] = run["id"]
        REPORT["evaluation_results"] = results
        analytics = request(client, "GET", base + "/analytics")
        check("analytics_reads_real_feedback_and_messages", analytics["answerCount"] >= 1 and analytics["refusalCount"] >= 1)
        request(client, "POST", base + "/offline")
        check("offline_blocks_ask", client.post(API + ask, json={"content": "等价类"}).status_code == 409)
        check("offline_blocks_regeneration", client.post(API + "/messages/" + answer["userMessageId"] + "/regenerations").status_code == 409)
        request(client, "POST", base + "/publish")
        REPORT["passed"] = len(REPORT["checks"])
        output = ROOT / "data/evaluation/local-smoke-latest.json"
        output.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"checks_passed": REPORT["passed"], "kb_id": kb_id, "run_id": run["id"], "report": str(output)}, ensure_ascii=False))


if __name__ == "__main__":
    if "--modes-only" in sys.argv:
        verify_answer_modes()
    else:
        main()
