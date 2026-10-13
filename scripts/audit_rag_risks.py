"""Reproduce risk boundaries on isolated stores/local HTTP; never mutate active corpus."""
import hashlib
import json
import os
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'rag-service'))
sys.path.insert(0, str(ROOT / 'scripts'))
from app.config import Settings
from app.index_store import JsonIndexStore
from app.providers import ChatProvider, ChatProviderError
from app.retrieval import Chunk, MemoryIndexStore
from app.schemas import GenerateRequest, IndexDocumentRequest
from app.service import RagService
from smoke_local import CONFIG, sql
import httpx


def main():
    output = Path(os.environ['RAG_AUDIT_DIR'])
    active = Path(os.environ['RAG_INDEX_PATH'])
    before = hashlib.sha256(active.read_bytes()).hexdigest()
    report = {'executed_at': datetime.now(timezone.utc).isoformat(), 'scope': 'Isolated deterministic risk probes; not throughput or external-provider failure tests', 'probes': [], 'http_scope_checks': []}
    def probe(name, expected, actual, passed):
        report['probes'].append({'name': name, 'expected': expected, 'actual': actual, 'passed': bool(passed)})
    # Real excerpt, inverted interpretation: membership validation alone accepts it.
    review = {'aspects': [{'id': 1, 'status': 'supported', 'text': '没有权限也能执行该操作。',
                           'evidence': [{'source': 1, 'quote': '这项操作在没有权限时不能执行。'}]}]}
    accepted = RagService._validate_coverage_review(json.dumps(review, ensure_ascii=False), ['权限条件'], [{'content': '这项操作在没有权限时不能执行。'}])
    probe('quote_membership_does_not_validate_entailment', 'contradictory interpretation is not semantically supported', accepted.model_dump(), False)

    # Real loopback transport sends bytes frequently enough to avoid read timeout,
    # while exceeding the configured timeout in total wall clock.
    class SlowHandler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            self.rfile.read(int(self.headers['Content-Length']))
            payload = json.dumps({'choices': [{'finish_reason': 'stop', 'message': {'content': 'local-test-output'}}]}).encode()
            self.send_response(200); self.send_header('Content-Length', str(len(payload))); self.end_headers()
            for start in range(0, len(payload), 12):
                self.wfile.write(payload[start:start + 12]); self.wfile.flush(); time.sleep(.06)
    server = ThreadingHTTPServer(('127.0.0.1', 0), SlowHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    try:
        provider = ChatProvider('openai-compatible', f'http://127.0.0.1:{server.server_port}', 'local-test-only', 'local-test', .15)
        start = time.perf_counter()
        result = provider.generate_general('local timing probe')
        elapsed = time.perf_counter() - start
        probe('strict_total_timeout', 'wall-clock deadline <= configured 0.15 seconds', {'configured_seconds': .15, 'wall_seconds': elapsed, 'returned': result}, elapsed <= .15)
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=2)

    with tempfile.TemporaryDirectory(prefix='rag-risk-', dir=ROOT / '.runtime/tmp') as temp:
        store = JsonIndexStore(str(Path(temp) / 'isolated-index.json'))
        service = RagService(Settings(chat_provider='disabled'), store)
        payload = IndexDocumentRequest(document_version_id='isolated-v', document_name='isolated.txt', chunks=['alpha software testing source evidence'])
        service.index_document('isolated-kb', payload)
        request = GenerateRequest(question='alpha software testing', query='alpha software testing', knowledge_base_id='isolated-kb', document_version_ids=['isolated-v'], answer_mode='extractive')
        deleted = threading.Event(); release = threading.Event()
        original_delete = store.delete_version
        def pausing_delete(version):
            count = original_delete(version); deleted.set()
            if not release.wait(5): raise RuntimeError('probe synchronization timeout')
            return count
        store.delete_version = pausing_delete
        failures = []
        def writer():
            try: service.index_document('isolated-kb', payload)
            except Exception as error: failures.append(type(error).__name__)
        worker = threading.Thread(target=writer); worker.start()
        try:
            if not deleted.wait(5): raise RuntimeError('writer did not reach gap')
            during = service.generate(request)
            probe('atomic_version_replacement', 'concurrent reader sees complete old or new version', {'during_status': during.status, 'during_reason': during.evidence_reason, 'isolated_persisted_count': len(json.loads(store.path.read_text(encoding='utf-8')))}, during.status == 'ANSWERED')
        finally:
            release.set(); worker.join(5)
        probe('isolated_replacement_recovers', 'new version available after writer finishes', {'writer_errors': failures, 'status': service.generate(request).status}, not failures and service.generate(request).status == 'ANSWERED')

        # Force a real concurrent dictionary mutation exactly during enumeration.
        entered = threading.Event(); proceed = threading.Event()
        class PausingDict(dict):
            def values(self):
                iterator = iter(super().values())
                first = next(iterator)
                yield first
                entered.set()
                if not proceed.wait(5): raise RuntimeError('reader barrier timeout')
                yield from iterator
        store.chunks = PausingDict(store.chunks)
        observed = []
        def reader():
            try: store.list_chunks('isolated-kb', ['isolated-v']); observed.append('completed')
            except Exception as error: observed.append(type(error).__name__ + ': ' + str(error))
        reader_thread = threading.Thread(target=reader); reader_thread.start()
        if not entered.wait(5): raise RuntimeError('reader did not reach iterator')
        # The production writer holds lock; reader does not acquire it.
        with store.lock:
            store.chunks['new-chunk'] = Chunk('new-chunk', 'isolated-kb', 'isolated-v', 'isolated.txt', 1, 'alpha new', [0.0] * 384)
        proceed.set(); reader_thread.join(5)
        probe('read_during_locked_dictionary_write', 'reader returns stable snapshot without iterator exception', observed, observed == ['completed'])

    # No external model calls: verify the deployed allow-list/single-KB boundary.
    rows = [r.split('\t') for r in sql("SELECT k.id,k.code,d.current_version_id FROM knowledge_bases k JOIN documents d ON d.knowledge_base_id=k.id JOIN document_versions v ON v.id=d.current_version_id WHERE k.deleted_at IS NULL AND k.status='PUBLISHED' AND d.deleted_at IS NULL AND d.status='PUBLISHED' AND v.parse_status='READY';").splitlines()]
    headers = {'Authorization': 'Bearer ' + CONFIG['RAG_SERVICE_TOKEN']}
    with httpx.Client(timeout=30, trust_env=False) as client:
        os_rows = [row for row in rows if row[1] == '408-operating-systems']
        ds_rows = [row for row in rows if row[1] == '408-data-structures']
        for name, scope, versions, question, expected in [
            ('empty_allowlist', ds_rows[0][0], [], '二叉搜索树是什么？', 'REFUSED'),
            ('single_os_scope_btree_refused', os_rows[0][0], [r[2] for r in os_rows], 'B树是什么？', 'REFUSED'),
            ('single_ds_scope_btree_answered', ds_rows[0][0], [r[2] for r in ds_rows], 'B树是什么？', 'ANSWERED'),
        ]:
            response = client.post('http://127.0.0.1:8000/internal/v1/answers/generate', headers=headers, json={'question': question, 'query': question, 'knowledge_base_id': scope, 'document_version_ids': versions, 'answer_mode': 'extractive'})
            body = response.json()
            report['http_scope_checks'].append({'name': name, 'http_status': response.status_code, 'expected_status': expected, 'actual': body, 'passed': response.status_code == 200 and body.get('status') == expected and all(c['document_version_id'] in versions for c in body.get('citations', []))})
    report['index_unchanged'] = hashlib.sha256(active.read_bytes()).hexdigest() == before
    report['temporary_index_cleaned'] = not Path(temp).exists()
    (output / 'risks.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'probes': report['probes'], 'http_scope_checks': [{k: c[k] for k in ['name', 'passed', 'http_status']} for c in report['http_scope_checks']], 'index_unchanged': report['index_unchanged']}, ensure_ascii=False))

if __name__ == '__main__': main()
