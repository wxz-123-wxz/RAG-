"""Explicit real-provider smoke checks using existing published test/corpus KBs.

Requires --run-real-model. Makes a small number of billable model calls, stores actual
answers/citations/errors, and never reads or prints the model API key.
"""
import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone

import httpx
from smoke_local import ROOT, CONFIG, API, sql


def context_regression():
    """Repeat the boundary/coverage regression without creating documents/indexes.

    Workflow and phrase assertions are not a semantic correctness benchmark.
    Save actual model outputs for review, including failures; clean only our chats.
    """
    from pathlib import Path
    sys.path.insert(0, str(ROOT / "rag-service"))
    from app.config import Settings
    from app.index_store import JsonIndexStore
    from app.schemas import GenerateRequest
    from app.service import RagService

    report_path = ROOT / "data/evaluation/local-smoke-latest.json"
    prior = json.loads(report_path.read_text(encoding="utf-8"))
    run = {"stage":"context_regression_http", "executed_at":datetime.now(timezone.utc).isoformat(),
           "scope":"Fixed engineering/phrase checks with real provider; not calibrated quality/performance metrics",
           "checks":[], "answers":[], "corpus_regression":[], "conversation_cleanup":{}}
    def check(name, condition):
        run["checks"].append({"name":name, "passed":bool(condition)})
        print(("PASS " if condition else "FAIL ") + name, flush=True)
    def finish():
        latest = json.loads(report_path.read_text(encoding="utf-8"))
        latest.setdefault("context_coverage_verifications", []).append(run)
        report_path.write_text(json.dumps(latest, ensure_ascii=False, indent=2), encoding="utf-8")

    index = Path(os.environ["RAG_INDEX_PATH"])
    before_hash = hashlib.sha256(index.read_bytes()).hexdigest()
    run["source_hashes"] = {name:hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in (
        "rag-service/app/retrieval.py", "rag-service/app/service.py", "rag-service/app/providers.py", "rag-service/app/schemas.py")}
    try:
        rows = [r.split("\t") for r in sql("""SELECT d.knowledge_base_id,d.current_version_id,k.code
            FROM documents d JOIN knowledge_bases k ON k.id=d.knowledge_base_id
            JOIN document_versions v ON v.id=d.current_version_id
            WHERE d.deleted_at IS NULL AND k.deleted_at IS NULL AND k.status='PUBLISHED'
            AND d.status='PUBLISHED' AND v.parse_status='READY';""").splitlines()]
        service = RagService(Settings(), JsonIndexStore(str(index)))
        for case in prior["final_lexical_regression"]["checks"]:
            scope = [(k,v) for k,v,code in rows if code == case["course"]]
            if not scope:
                raise RuntimeError("Required published regression library is unavailable")
            result = service.generate(GenerateRequest(question=case["question"],query=case["question"],
                knowledge_base_id=scope[0][0],document_version_ids=[v for k,v in scope],answer_mode="extractive"))
            run["corpus_regression"].append({"question":case["question"],"course":case["course"],
                "expected":case["expected"],"actual":result.status,"passed":result.status == case["expected"]})
        check("existing_36_corpus_cases",len(run["corpus_regression"]) == 36 and all(c["passed"] for c in run["corpus_regression"]))

        with httpx.Client(timeout=90, trust_env=False) as client:
            def request(method,path,expected=200,**kwargs):
                response = client.request(method,API+path,**kwargs)
                if response.status_code != expected:
                    raise RuntimeError(f"{method} {path}: HTTP {response.status_code}")
                return response.json().get("data") if response.content else None
            for name,url in (("rag","http://127.0.0.1:8000/health/ready"),
                             ("backend","http://127.0.0.1:8080/health/live"),("frontend","http://127.0.0.1:5173")):
                check(name + "_health",client.get(url).status_code == 200)
            auth = request("POST","/auth/login",json={"username":"admin","password":CONFIG["LOCAL_ADMIN_PASSWORD"]})
            client.headers["Authorization"] = "Bearer " + auth["accessToken"]
            conversation = request("POST","/conversations",201,json={"knowledgeBaseId":"all","title":"上下文与覆盖固定回归-临时"})
            cid = str(conversation["id"])
            run["conversation_id"] = cid
            try:
                cases = [
                    ("comparison","请比较 B 树和二叉搜索树的查找过程及适用场景。","ANSWERED","MODEL_REPORTED_COMPLETE"),
                    ("bst_qualifiers","二叉搜索树是什么？","ANSWERED",None),
                    ("deadlock","死锁的四个必要条件是什么？","ANSWERED","MODEL_REPORTED_COMPLETE"),
                    ("true_gap","什么是等价类划分？请同时说明本课程期末考试的具体日期、教室及监考教师。","ANSWERED","MODEL_REPORTED_PARTIAL"),
                    ("no_evidence","量子纠缠的数学证明是什么？","REFUSED","INSUFFICIENT"),
                ]
                for name,question,status,state in cases:
                    answer = request("POST","/conversations/"+cid+"/messages",json={"content":question,"answerMode":"generated"})["assistantMessage"]
                    run["answers"].append({"case":name,"question":question,"response":answer})
                    check(name + "_status",answer["status"] == status and (state is None or answer["evidenceState"] == state))
                    citations = request("GET","/messages/"+answer["id"]+"/citations")
                    check(name + "_citations_read_back",citations == answer["citations"] and (bool(citations) if status == "ANSWERED" else not citations))
                    quote_checks = []
                    for c in citations:
                        if not str(c["chunkId"]).isdigit() or not str(c["documentVersionId"]).isdigit():
                            raise RuntimeError("Invalid citation ID")
                        raw = sql("SELECT HEX(content) FROM document_chunks WHERE id="+c["chunkId"]+" AND document_version_id="+c["documentVersionId"]+" AND chunk_no="+str(c["chunkNo"])+";")
                        quote_checks.append(bool(raw) and bytes.fromhex(raw).decode("utf-8") == c["quote"])
                    check(name + "_exact_database_quotes",all(quote_checks))
                    if name == "comparison":
                        check("comparison_four_aspects_mentioned",all(term in answer["content"] for term in ["外", "根", "二叉搜索树", "有序"]))
                    if name == "bst_qualifiers":
                        check("bst_left_and_right_qualifiers",all(term in answer["content"] for term in ["左子树","右子树"]))
                        check("bst_original_prefix_available",any("左子树中所有节点" in c["quote"] for c in citations))
                    if name == "true_gap":
                        check("real_missing_requirements_preserved",all(term in answer["content"] for term in ["日期","教室","监考"]))
                    print(json.dumps({"case":name,"state":answer["evidenceState"],"content":answer["content"]},ensure_ascii=False),flush=True)
                source = run["answers"][0]["response"]
                general = request("POST","/messages/"+source["id"]+"/general-answer",json={"confirm":True})["assistantMessage"]
                run["general_answer"] = general
                check("general_stays_independent",general["answerOrigin"] == "GENERAL" and general["evidenceState"] == "NOT_APPLICABLE" and general["citations"] == [])
                history = request("GET","/conversations/"+cid)["messages"]
                check("grounded_history_not_overwritten",all(any(str(m["id"]) == a["response"]["id"] and m["content"] == a["response"]["content"] for m in history) for a in run["answers"]))
            finally:
                request("DELETE","/conversations/"+cid,204)
                run["conversation_cleanup"][cid] = client.get(API+"/conversations/"+cid).status_code == 404
                check("temporary_conversation_deleted",run["conversation_cleanup"][cid])
    except Exception as error:
        run["error"] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        raise
    finally:
        check("index_unchanged",hashlib.sha256(index.read_bytes()).hexdigest() == before_hash)
        run["status"] = "PASSED" if "error" not in run and all(c["passed"] for c in run["checks"]) else "FAILED"
        finish()
        print(json.dumps({"status":run["status"],"passed":sum(c["passed"] for c in run["checks"]),"total":len(run["checks"])},ensure_ascii=False))
    if run["status"] != "PASSED":
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-real-model", action="store_true")
    parser.add_argument("--context-regression", action="store_true", help="Existing corpus: context, coverage, citations, fallback and cleanup")
    args = parser.parse_args()
    if not args.run_real_model:
        parser.error("Supply --run-real-model to authorize real provider calls with test documents")
    if args.context_regression:
        return context_regression()
    report_path = ROOT / "data/evaluation/local-smoke-latest.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    run = {"executed_at": datetime.now(timezone.utc).isoformat(), "checks": [], "answers": [],
           "scope": "Self-written integration samples only; NOT a formal quality/performance experiment"}

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        run["checks"].append(name)
        print("PASS", name, flush=True)

    with httpx.Client(timeout=45, trust_env=False) as client:
        def request(method, path, expected=200, **kwargs):
            response = client.request(method, API + path, **kwargs)
            if response.status_code != expected:
                try:
                    code = response.json().get("error", {}).get("code", "HTTP_ERROR")
                except ValueError:
                    code = "INVALID_HTTP_RESPONSE"
                raise RuntimeError(f"{response.status_code}: {code}")
            return response.json().get("data") if response.content else None

        def save_answer(question, result):
            answer = result["assistantMessage"]
            run["answers"].append({"question": question, **answer})
            print(json.dumps({"status": answer["status"], "origin": answer["answerOrigin"],
                "coverage": answer["evidenceState"], "model": answer.get("model"),
                "citations": len(answer["citations"])}, ensure_ascii=False), flush=True)
            return answer

        try:
            auth = request("POST", "/auth/login", json={"username": "admin", "password": CONFIG["LOCAL_ADMIN_PASSWORD"]})
            client.headers["Authorization"] = "Bearer " + auth["accessToken"]
            capabilities = request("GET", "/system/capabilities")
            check("real_provider_configured", capabilities["chatConfigured"])
            run["configured_model"] = capabilities["chatModel"]
            conv = request("POST", "/conversations", 201, json={"knowledgeBaseId": report["knowledge_base_id"],
                "title": "DeepSeek 真实模型联调（自制测试资料）"})
            run["conversation_id"] = conv["id"]
            ask = "/conversations/" + conv["id"] + "/messages"

            question = "什么是等价类划分？"
            answer = save_answer(question, request("POST", ask, json={"content": question, "answerMode": "generated"}))
            check("real_grounded_answer", answer["status"] == "ANSWERED" and answer["answerOrigin"] == "KNOWLEDGE_BASE" and len(answer["citations"]) > 0)
            for citation in answer["citations"]:
                original = sql("SELECT content FROM document_chunks WHERE id=" + citation["chunkId"] + ";")
                check("real_citation_matches_database_" + str(citation["number"]), original == citation["quote"].replace("\n", "\\n"))

            question = "什么是等价类划分？请同时说明本课程期末考试的具体日期、教室及监考教师。"
            partial = save_answer(question, request("POST", ask, json={"content": question, "answerMode": "generated"}))
            check("real_model_reports_partial_coverage", partial["status"] == "ANSWERED" and partial["evidenceState"] == "MODEL_REPORTED_PARTIAL")

            question = "软件设计中，六边形架构的端口和适配器各有什么作用？"
            absent = save_answer(question, request("POST", ask, json={"content": question, "answerMode": "generated"}))
            check("uncovered_question_refused_without_citations", absent["status"] == "REFUSED" and absent["citations"] == [])
            baseline = request("GET", "/knowledge-bases/" + report["knowledge_base_id"] + "/analytics")["answerCount"]
            general = save_answer(question, request("POST", "/messages/" + absent["id"] + "/general-answer", json={"confirm": True}))
            check("real_general_answer_is_separate", general["answerOrigin"] == "GENERAL" and general["citations"] == []
                  and "非知识库来源" in general["content"])
            check("real_general_excluded_from_kb_statistics", request("GET", "/knowledge-bases/" + report["knowledge_base_id"] + "/analytics")["answerCount"] == baseline)
            request("PUT", "/messages/" + answer["id"] + "/feedback", 204,
                    json={"helpful": True, "comment": "自制资料联调：引用原文回查一致；非正式质量评测"})
            check("real_answer_feedback_saved", request("GET", "/messages/" + answer["id"] + "/feedback")["helpful"])
            run["status"] = "PASSED"
        except Exception as error:
            run["status"] = "FAILED"
            run["error"] = str(error) if isinstance(error, (AssertionError, RuntimeError)) else type(error).__name__
            raise
        finally:
            run["passed"] = len(run["checks"])
            report.setdefault("real_model_verifications", []).append(run)
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"real_model_checks_passed": run["passed"], "model": run["configured_model"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
