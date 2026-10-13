import json
import os
from pathlib import Path

from app.retrieval import Chunk, MemoryIndexStore


class JsonIndexStore(MemoryIndexStore):
    def __init__(self, path: str):
        super().__init__()
        self.path = Path(path).expanduser().resolve()
        self._load()

    def _commit(self, updated: dict[str, Chunk]) -> None:
        # Persist completely before publishing the new in-memory generation.
        # A failed disk write leaves readers and the old JSON on the old version.
        self._save(updated)
        super()._commit(updated)

    def _load(self) -> None:
        if not self.path.exists():
            return
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        for item in payload:
            chunk = Chunk(**item)
            self.chunks[chunk.chunk_id] = chunk

    def _save(self, updated: dict[str, Chunk]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        payload = [
            {
                "chunk_id": chunk.chunk_id,
                "knowledge_base_id": chunk.knowledge_base_id,
                "document_version_id": chunk.document_version_id,
                "document_name": chunk.document_name,
                "chunk_no": chunk.chunk_no,
                "content": chunk.content,
                "vector": chunk.vector,
            }
            for chunk in updated.values()
        ]
        temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        os.replace(temporary, self.path)
