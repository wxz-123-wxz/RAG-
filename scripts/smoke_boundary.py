"""Real MySQL/Spring/FastAPI integration with a LOCAL model test double.

Never measures model quality or calls an external model. Reuses the existing smoke KB.
Starts temporary RAG 8001 and Spring 8081, then stops them. Requires local environment.
"""
import json
import os
import subprocess
import sys
import threading
import time
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
from smoke_local import ROOT, CONFIG, sql


def main():
    report_path = ROOT / "data/evaluation/local-smoke-latest.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    state = {"coverage": "partial", "calls": [], "invalid": False}
    checks = []
    processes = []
    server = None
    conversation_id = None
    api = "http://127.0.0.1:8081/api/v1"

    def check(name, value):
        assert value, name
        checks.append(name)
        print("PASS", name, flush=True)

    class ModelHandler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            state["calls"].append(payload)
            prompt = payload["messages"][0]["content"]
            if "本次没有知识库资料" in prompt:
                content = "这是一条本地测试替身返回的通用说明，未联网核验。"
            elif state["invalid"]:
                content = "invalid unstructured output"
            else:
                content = json.dumps({"coverage": state["coverage"], "claims": [] if state["coverage"] == "insufficient" else [
                    {"text": "等价类划分从输入类别中选取代表值进行测试。", "citations": [1]}],
                    "missing_aspects": ["资料未提供其他方法的完整对比。"] if state["coverage"] == "partial" else []}, ensure_ascii=False)
            output = json.dumps({"choices": [{"message": {"content": content}}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(output)))
            self.end_headers()
            self.wfile.write(output)

    def start(args, cwd, env):
        lines = deque(maxlen=20)
        proc = subprocess.Popen(args, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        threading.Thread(target=lambda: [lines.append(line) for line in proc.stdout], daemon=True).start()
        processes.append(proc)
        return proc

    def wait_up(url, proc):
        for _ in range(120):
            if proc.poll() is not None:
                raise RuntimeError("Temporary test service exited; no credentials or raw logs printed")
            try:
                if httpx.get(url, timeout=1, trust_env=False).status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(.3)
        raise RuntimeError("Temporary service did not become healthy")

    with httpx.Client(timeout=45, trust_env=False) as client:
        def request(method, path, expected=200, **kwargs):
            response = client.request(method, api + path, **kwargs)
            assert response.status_code == expected, (path, response.status_code)
            return response.json().get("data") if response.content else None

        try:
            for port in (8001, 8081):
                import socket
                with socket.socket() as probe:
                    if probe.connect_ex(("127.0.0.1", port)) == 0:
                        raise RuntimeError(f"Port {port} occupied; test refuses to replace another service")
            server = ThreadingHTTPServer(("127.0.0.1", 0), ModelHandler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            env = os.environ | CONFIG | {"PYTHONDONTWRITEBYTECODE": "1", "RAG_CHAT_PROVIDER": "openai-compatible",
                "RAG_CHAT_BASE_URL": f"http://127.0.0.1:{server.server_port}/v1", "RAG_CHAT_MODEL": "local-test-double",
                "RAG_CHAT_API_KEY": "test-only-not-a-real-key", "RAG_ANSWER_MODE": "extractive",
                "APP_BOOTSTRAP_ADMIN_PASSWORD": "", "APP_RAG_BASE_URL": "http://127.0.0.1:8001"}
            rag = start([sys.executable, "-B", "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8001"], ROOT / "rag-service", env)
            wait_up("http://127.0.0.1:8001/health/ready", rag)
            # Oracle's javapath launcher spawns another JVM; resolve java.home first.
            from pathlib import Path
            probe = subprocess.run(["java", "-XshowSettings:properties", "-version"], env=env,
                                   capture_output=True, text=True, encoding="utf-8", errors="replace", check=True)
            java_home = next(line.split("=", 1)[1].strip() for line in probe.stderr.splitlines()
                             if line.strip().startswith("java.home ="))
            java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
            backend = start([java, "-XX:-UsePerfData", "-Djava.io.tmpdir=" + str(ROOT / ".runtime/tmp"),
                "-Duser.home=" + str(ROOT / ".runtime/home"), "-jar", str(ROOT / "backend/target/rag-qa-backend-0.1.0-SNAPSHOT.jar"),
                "--server.port=8081"], ROOT / "backend", env)
            wait_up("http://127.0.0.1:8081/health/live", backend)
            auth = request("POST", "/auth/login", json={"username": "admin", "password": CONFIG["LOCAL_ADMIN_PASSWORD"]})
            client.headers["Authorization"] = "Bearer " + auth["accessToken"]
            kb = report["knowledge_base_id"]
            conv = request("POST", "/conversations", 201, json={"knowledgeBaseId": kb, "title": "证据边界本地替身联调"})
            conversation_id = conv["id"]
            ask = "/conversations/" + conversation_id + "/messages"
            payload = {"content": "什么是等价类划分？", "answerMode": "generated"}
            partial = request("POST", ask, json=payload)["assistantMessage"]
            check("partial_has_real_citations_and_coverage", partial["evidenceState"] == "MODEL_REPORTED_PARTIAL" and bool(partial["citations"]))
            check("partial_coverage_persisted", sql("SELECT evidence_state FROM messages WHERE id=" + partial["id"] + ";") == "MODEL_REPORTED_PARTIAL")
            check("actual_retrieved_source_sent_to_local_model", "有效等价类" in state["calls"][-1]["messages"][0]["content"])
            fallback = "/messages/" + partial["id"] + "/general-answer"
            before_calls = len(state["calls"])
            for consent in ({}, {"confirm": False}):
                request("POST", fallback, 400, json=consent)
            check("missing_or_false_confirmation_never_calls_model", len(state["calls"]) == before_calls)
            before_count = request("GET", "/knowledge-bases/" + kb + "/analytics")["answerCount"]
            general = request("POST", fallback, json={"confirm": True})["assistantMessage"]
            check("general_origin_and_no_citations", general["answerOrigin"] == "GENERAL" and general["citations"] == [])
            check("general_origin_persisted", sql("SELECT answer_origin FROM messages WHERE id=" + general["id"] + ";") == "GENERAL")
            check("general_has_no_saved_citations", request("GET", "/messages/" + general["id"] + "/citations") == [])
            check("general_excluded_from_knowledge_base_statistics", request("GET", "/knowledge-bases/" + kb + "/analytics")["answerCount"] == before_count)
            sent = state["calls"][-1]["messages"]
            check("general_sends_only_original_question", len(sent) == 2 and sent[-1]["content"] == payload["content"] and "有效等价类" not in json.dumps(sent, ensure_ascii=False))
            detail = request("GET", "/conversations/" + conversation_id)
            check("history_preserves_source_identity", next(m for m in detail["messages"] if m["id"] == general["id"])["answerOrigin"] == "GENERAL")
            request("POST", "/messages/" + general["id"] + "/general-answer", 409, json={"confirm": True})
            check("general_cannot_chain_fallback", True)
            state["coverage"] = "complete"
            complete = request("POST", ask, json=payload)["assistantMessage"]
            check("complete_coverage_exposed", complete["evidenceState"] == "MODEL_REPORTED_COMPLETE")
            independent = request("POST", "/messages/" + complete["id"] + "/general-answer", json={"confirm": True})["assistantMessage"]
            check("complete_answer_supports_explicit_independent_answer",
                  independent["answerOrigin"] == "GENERAL" and independent["citations"] == [])
            state["coverage"] = "insufficient"
            refused = request("POST", ask, json=payload)["assistantMessage"]
            check("model_reported_insufficient_refuses", refused["status"] == "REFUSED" and refused["citations"] == [] and refused["answerOrigin"] == "NONE")
            before_calls = len(state["calls"])
            absent = request("POST", ask, json={"content": "xylophonequasarzz", "answerMode": "generated"})["assistantMessage"]
            check("retrieval_miss_never_calls_enabled_model", absent["status"] == "REFUSED" and len(state["calls"]) == before_calls)
            request("POST", "/messages/" + absent["id"] + "/general-answer", json={"confirm": True})
            check("refused_answer_supports_explicit_general", True)
            state["invalid"] = True
            before = len(request("GET", "/conversations/" + conversation_id)["messages"])
            invalid = client.post(api + ask, json=payload)
            check("invalid_coverage_errors_without_silent_fallback", invalid.status_code == 503 and invalid.json()["error"]["code"] == "CHAT_MODEL_INVALID_RESPONSE")
            check("invalid_generation_rolls_back_messages", len(request("GET", "/conversations/" + conversation_id)["messages"]) == before)
            request("POST", ask, 400, json={"content": "q", "answerMode": "general"})
            check("ordinary_ask_cannot_bypass_consent", True)
            report.setdefault("boundary_verifications", []).append({"executed_at": datetime.now(timezone.utc).isoformat(),
                "checks": checks, "passed": len(checks), "model": "local HTTP test double; NOT a real model",
                "external_model_called": False, "scope": "Real MySQL + Spring Boot + FastAPI; response logic only, not model quality"})
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        finally:
            try:
                if conversation_id:
                    request("DELETE", "/conversations/" + conversation_id, 204)
            finally:
                for proc in reversed(processes):
                    proc.terminate()
                    try:
                        proc.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=5)
                if server:
                    server.shutdown()
                    server.server_close()
    print(json.dumps({"boundary_checks_passed": len(checks), "external_model_called": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
