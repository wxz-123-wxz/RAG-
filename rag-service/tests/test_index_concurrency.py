import json
import threading

import pytest
from app.config import Settings
from app.index_store import JsonIndexStore
from app.retrieval import Chunk
from app.schemas import GenerateRequest, IndexDocumentRequest
from app.service import RagService


def chunk(key, content, version='v'):
    return Chunk(key, 'kb', version, 'source.txt', 0, content, [0.0] * 384)


def test_replacement_publishes_whole_version_without_empty_intermediate_state(tmp_path, monkeypatch):
    store = JsonIndexStore(str(tmp_path / 'index.json'))
    store.replace_version('v', [chunk('old', 'old content')])
    entered, release = threading.Event(), threading.Event()
    original_save = store._save
    def blocked_save(updated):
        entered.set()
        assert release.wait(3)
        original_save(updated)
    monkeypatch.setattr(store, '_save', blocked_save)
    failures = []
    def write():
        try: store.replace_version('v', [chunk('new', 'new content')])
        except Exception as error: failures.append(error)
    with store.read_snapshot():
        worker = threading.Thread(target=write)
        worker.start()
        try:
            assert entered.wait(3)
            assert [c.chunk_id for c in store.list_chunks('kb', ['v'])] == ['old']
            assert [c['chunk_id'] for c in json.loads(store.path.read_text())] == ['old']
        finally:
            release.set()
            worker.join(3)
        # A request keeps its original generation even after writer finishes.
        assert [c.chunk_id for c in store.list_chunks('kb', ['v'])] == ['old']
    assert not failures and not worker.is_alive()
    assert [c.chunk_id for c in store.list_chunks('kb', ['v'])] == ['new']
    assert [c['chunk_id'] for c in json.loads(store.path.read_text())] == ['new']


def test_failed_persistence_does_not_publish_new_memory_or_erase_disk(tmp_path, monkeypatch):
    store = JsonIndexStore(str(tmp_path / 'index.json'))
    store.replace_version('v', [chunk('old', 'old')])
    before = store.path.read_bytes()
    def fail(updated):
        raise OSError('simulated disk failure')
    monkeypatch.setattr(store, '_save', fail)
    with pytest.raises(OSError):
        store.replace_version('v', [chunk('new', 'new')])
    assert store.path.read_bytes() == before
    assert [c.chunk_id for c in store.list_chunks('kb', ['v'])] == ['old']


def test_reader_iterator_remains_valid_during_writer_commit(tmp_path):
    store = JsonIndexStore(str(tmp_path / 'index.json'))
    store.upsert([chunk('a', 'old'), chunk('b', 'old')])
    iterator = iter(store._read().values())
    assert next(iterator).chunk_id == 'a'
    worker = threading.Thread(target=lambda: store.upsert([chunk('c', 'new')]))
    worker.start(); worker.join(3)
    assert not worker.is_alive()
    assert [c.chunk_id for c in iterator] == ['b']
    assert store.count() == 3


def test_generation_uses_same_snapshot_for_hits_and_neighbours(tmp_path, monkeypatch):
    store = JsonIndexStore(str(tmp_path / 'index.json'))
    service = RagService(Settings(chat_provider='disabled'), store)
    source = '二叉搜索树的左子树节点小于根节点，右子树节点大于根节点。'
    service.index_document('kb', IndexDocumentRequest(document_version_id='v', document_name='tree', chunks=[source]))
    original_search = service.retrieval.search
    def search_then_delete(request):
        result = original_search(request)
        worker = threading.Thread(target=lambda: store.delete_version('v'))
        worker.start(); worker.join(3)
        assert not worker.is_alive()
        return result
    monkeypatch.setattr(service.retrieval, 'search', search_then_delete)
    class Model:
        def generate(self, question, evidence, history):
            assert evidence[0]['content'] == source
            return json.dumps({'coverage':'complete', 'claims':[{'text':'左子树小于根节点。','citations':[1]}], 'missing_aspects':[]})
    service.chat = Model()
    result = service.generate(GenerateRequest(question='二叉搜索树是什么？', query='ignored', knowledge_base_id='kb', document_version_ids=['v'], answer_mode='generated'))
    assert result.status == 'ANSWERED' and result.citations[0].quote == source
    assert store.count() == 0


def test_snapshot_still_obeys_empty_allowlist_and_version_boundaries(tmp_path):
    store = JsonIndexStore(str(tmp_path / 'index.json'))
    store.upsert([chunk('a', 'old'), chunk('b', 'other', 'other')])
    with store.read_snapshot():
        assert store.list_chunks('kb', []) == []
        assert store.list_chunks('private', ['v']) == []
        assert [c.chunk_id for c in store.list_chunks('kb', ['v'])] == ['a']
