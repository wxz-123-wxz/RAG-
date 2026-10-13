import math
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Protocol
from threading import RLock
from contextlib import contextmanager
from contextvars import ContextVar

from app.embeddings import EmbeddingProvider, cosine_similarity
from app.schemas import Evidence, SearchRequest
from app.relevance import evidence_body, focus_priority, has_relevant_evidence, is_concept_overview, lexical_terms, query_focus, query_subjects


def chunk_overlap(left: str, right: str) -> int:
    """Exact suffix/prefix overlap, bounded by the supported ingestion overlap.

    A short/common word is not proof that two passages form continuous text.
    No normalization: returned offsets must still refer to the stored originals.
    """
    for size in range(min(len(left), len(right), 1000), 19, -1):
        if left.endswith(right[:size]):
            return size
    return 0


def evidence_passages(evidence: list[Evidence], anchors: list[Evidence]) -> list[list[Evidence]]:
    """Bound each passage to an anchor and its immediate overlapping neighbours.

    Adjacent anchors must not transitively turn a whole chapter into one citation.
    """
    positions = {(item.document_version_id, item.document_name, item.chunk_no): item for item in evidence}
    passages = []
    seen = set()
    for anchor in anchors:
        current = positions.get((anchor.document_version_id, anchor.document_name, anchor.chunk_no))
        if current is None or current.chunk_id != anchor.chunk_id:
            continue
        group = [current]
        for offset in (-1, 1):
            neighbour = positions.get((anchor.document_version_id, anchor.document_name, anchor.chunk_no + offset))
            if neighbour is None:
                continue
            left, right = (neighbour, current) if offset == -1 else (current, neighbour)
            if chunk_overlap(left.content, right.content):
                group.insert(0, neighbour) if offset == -1 else group.append(neighbour)
        key = tuple(item.chunk_id for item in group)
        if key not in seen:
            passages.append(group)
            seen.add(key)
    return passages


def passage_text(passage: list[Evidence]) -> str:
    text = passage[0].content
    for left, right in zip(passage, passage[1:]):
        text += right.content[chunk_overlap(left.content, right.content):]
    return text


@dataclass(slots=True)
class Chunk:
    chunk_id: str
    knowledge_base_id: str
    document_version_id: str
    document_name: str
    chunk_no: int
    content: str
    vector: list[float]


class IndexStore(Protocol):
    def upsert(self, chunks: list[Chunk]) -> None: ...
    def delete_version(self, document_version_id: str) -> int: ...
    def list_chunks(self, knowledge_base_id: str, version_ids: list[str]) -> list[Chunk]: ...
    def count(self) -> int: ...
    def replace_version(self, version_id: str, chunks: list[Chunk]) -> None: ...


@dataclass
class MemoryIndexStore:
    chunks: dict[str, Chunk] = field(default_factory=dict)
    lock: RLock = field(default_factory=RLock, repr=False)
    _view: ContextVar = field(default_factory=lambda: ContextVar('index_snapshot', default=None), repr=False)

    def _commit(self, updated: dict[str, Chunk]) -> None:
        self.chunks = updated

    @contextmanager
    def read_snapshot(self):
        """One immutable dictionary generation for all reads in one request.

        Writers replace dictionaries/chunks, never mutate published objects.
        No store lock is held while retrieving or waiting on the model.
        """
        with self.lock:
            current = self._view.get()
            view = self.chunks if current is None else current
        token = self._view.set(view)
        try:
            yield
        finally:
            self._view.reset(token)

    def _read(self):
        view = self._view.get()
        if view is not None:
            return view
        with self.lock:
            return self.chunks

    def upsert(self, chunks: list[Chunk]) -> None:
        with self.lock:
            updated = dict(self.chunks)
            updated.update((chunk.chunk_id, chunk) for chunk in chunks)
            self._commit(updated)

    def replace_version(self, version_id: str, chunks: list[Chunk]) -> None:
        if not chunks or any(c.document_version_id != version_id for c in chunks):
            raise ValueError('Replacement requires nonempty chunks of one version')
        with self.lock:
            updated = {key: chunk for key, chunk in self.chunks.items()
                       if chunk.document_version_id != version_id}
            updated.update((chunk.chunk_id, chunk) for chunk in chunks)
            self._commit(updated)

    def delete_version(self, document_version_id: str) -> int:
        with self.lock:
            updated = {key: chunk for key, chunk in self.chunks.items()
                       if chunk.document_version_id != document_version_id}
            deleted = len(self.chunks) - len(updated)
            self._commit(updated)
            return deleted

    def list_chunks(self, knowledge_base_id: str, version_ids: list[str]) -> list[Chunk]:
        return [
            chunk for chunk in self._read().values()
            if chunk.knowledge_base_id == knowledge_base_id
            and chunk.document_version_id in version_ids
        ]

    def count(self) -> int:
        return len(self._read())


class RetrievalEngine:
    def __init__(self, store: IndexStore, embedding: EmbeddingProvider, k1: float = 1.5, b: float = 0.75):
        self.store = store
        self.embedding = embedding
        self.k1 = k1
        self.b = b

    def search(self, request: SearchRequest, *, trace: dict | None = None) -> list[Evidence]:
        scopes = request.knowledge_base_ids or [request.knowledge_base_id]
        chunks = [chunk for kb in dict.fromkeys(scopes)
                  for chunk in self.store.list_chunks(kb, request.document_version_ids)]
        if not chunks:
            if trace is not None:
                trace.update(candidates=[], final_hits=[], query=request.query)
            return []

        subjects = query_subjects(request.query)
        focus = query_focus(request.query)
        facets = focus[1] if focus else []
        ranking_query = " ".join(subjects + facets)
        query_terms = lexical_terms(ranking_query)
        corpus_terms = [lexical_terms(evidence_body(chunk.content, keep_headings=True)) for chunk in chunks]
        bm25_scores = self._bm25(query_terms, corpus_terms)
        vector = self.embedding.embed(ranking_query or request.query)
        vector_scores = [max(0.0, cosine_similarity(vector, chunk.vector)) for chunk in chunks]

        bm25_order = sorted(range(len(chunks)), key=lambda idx: (-bm25_scores[idx], chunks[idx].chunk_id))
        vector_order = sorted(range(len(chunks)), key=lambda idx: (-vector_scores[idx], chunks[idx].chunk_id))
        bm25_ranks = self._rank_map([idx for idx in bm25_order if bm25_scores[idx] > 0], bm25_scores)
        vector_ranks = self._rank_map([idx for idx in vector_order if vector_scores[idx] > 0], vector_scores)

        if request.method == "VECTOR":
            ranked = vector_order
            scores = vector_scores
        else:
            fused: dict[int, float] = defaultdict(float)
            for idx, rank in bm25_ranks.items():
                fused[idx] += request.bm25_weight / (request.rrf_k + rank)
            for idx, rank in vector_ranks.items():
                fused[idx] += request.vector_weight / (request.rrf_k + rank)
            ranked = sorted(fused, key=lambda idx: (-fused[idx], chunks[idx].chunk_id))
            scores = [fused.get(idx, 0.0) for idx in range(len(chunks))]

        # Reject unsupported subjects before top-k truncation. Keep the existing
        # corpus/rank scores and persisted vector format unchanged.
        eligible = [idx for idx in ranked if has_relevant_evidence(request.query, chunks[idx].content)]
        if trace is not None:
            eligible_set = set(eligible)
            trace.update(query=request.query, ranking_query=ranking_query,
                candidates=[{'chunk_id': chunks[idx].chunk_id,
                    'document_version_id': chunks[idx].document_version_id, 'chunk_no': chunks[idx].chunk_no,
                    'score': scores[idx], 'bm25_score': bm25_scores[idx], 'vector_score': vector_scores[idx],
                    'bm25_rank': bm25_ranks.get(idx), 'vector_rank': vector_ranks.get(idx),
                    'pre_gate_rank': rank, 'topic_gate_passed': idx in eligible_set}
                    for rank, idx in enumerate(ranked, 1)])
        # Brief concept queries should not lose the definition to repeated
        # procedure keywords. Reserve one real overview, then retain RRF order.
        if request.method != "VECTOR":
            if focus:
                # Preserve RRF within each facet group. For comparisons, select
                # from each supported concept in turn before filling top-k, so
                # repeated hits for one side cannot crowd out the other side.
                pools = []
                for subject in subjects:
                    candidates = [idx for idx in eligible if has_relevant_evidence(subject, chunks[idx].content)]
                    # Separate facet pools also keep a short procedure passage
                    # from losing to repeated generic mentions of applications.
                    pools.append([sorted(
                        candidates,
                        key=lambda idx: focus_priority(subject, facet_group, chunks[idx].content, chunks[idx].document_name),
                        reverse=True,
                    ) for facet_group in ([[facet] for facet in facets] or [[]])])
                chosen = []
                seen = set()
                round_no = 0
                while len(chosen) < request.top_k:
                    added = False
                    for facet_pools in pools:
                        pool = facet_pools[round_no % len(facet_pools)]
                        candidate = next((idx for idx in pool if idx not in seen), None)
                        if candidate is not None:
                            chosen.append(candidate)
                            seen.add(candidate)
                            added = True
                            if len(chosen) == request.top_k:
                                break
                    if not added:
                        break
                    round_no += 1
                eligible = chosen + [idx for idx in eligible if idx not in seen]
            overview = next((idx for idx in eligible if is_concept_overview(request.query, chunks[idx].content)), None)
            if overview is not None:
                eligible = [overview] + [idx for idx in eligible if idx != overview]
        result = [
            Evidence(
                chunk_id=chunks[idx].chunk_id,
                document_version_id=chunks[idx].document_version_id,
                document_name=chunks[idx].document_name,
                chunk_no=chunks[idx].chunk_no,
                content=chunks[idx].content,
                score=scores[idx],
                vector_score=vector_scores[idx],
                bm25_score=bm25_scores[idx],
                bm25_rank=bm25_ranks.get(idx),
                vector_rank=vector_ranks.get(idx),
                fusion_rank=rank,
            )
            for rank, idx in enumerate(eligible[: request.top_k], start=1)
        ]
        if trace is not None:
            trace['final_hits'] = [item.model_dump() for item in result]
        return result

    def expand_context(self, request: SearchRequest, selected: list[Evidence], *,
                       max_chunks: int = 15, max_characters: int = 10000) -> list[Evidence]:
        """Read immediate overlapping neighbours for generation, never reindex.

        Original hits retain their order, scores and exact quotes. Neighbours are
        separately citable context, not ranked hits; they cannot create evidence
        when the initial retrieval gate failed. No recursive expansion.
        """
        scopes = request.knowledge_base_ids or [request.knowledge_base_id]
        chunks = [chunk for kb in dict.fromkeys(scopes)
                  for chunk in self.store.list_chunks(kb, request.document_version_ids)]
        by_id = {chunk.chunk_id: chunk for chunk in chunks}
        positions = {(chunk.knowledge_base_id, chunk.document_version_id, chunk.chunk_no): chunk for chunk in chunks}
        anchors = []
        seen = set()
        for item in selected:
            current = by_id.get(item.chunk_id)
            if (current is not None and current.document_version_id == item.document_version_id
                    and current.chunk_no == item.chunk_no and current.content == item.content
                    and item.chunk_id not in seen):
                anchors.append(item)
                seen.add(item.chunk_id)
        # Never discard a selected anchor simply to make room for context.
        result = list(anchors)
        characters = sum(len(item.content) for item in anchors)
        # Give missing sentence prefixes priority over continuations.
        for offset in (-1, 1):
            for item in anchors:
                current = by_id[item.chunk_id]
                neighbour = positions.get((current.knowledge_base_id, current.document_version_id, current.chunk_no + offset))
                if neighbour is None or neighbour.chunk_id in seen:
                    continue
                if any(neighbour.content == existing.content for existing in result):
                    continue  # An identical overlap adds no missing context.
                left, right = (neighbour, current) if offset == -1 else (current, neighbour)
                if (len(result) >= max_chunks or characters + len(neighbour.content) > max_characters
                        or not chunk_overlap(left.content, right.content)):
                    continue
                result.append(Evidence(chunk_id=neighbour.chunk_id, document_version_id=neighbour.document_version_id,
                    document_name=neighbour.document_name, chunk_no=neighbour.chunk_no, content=neighbour.content,
                    score=0.0, fusion_rank=0))
                seen.add(neighbour.chunk_id)
                characters += len(neighbour.content)
        return result

    def _bm25(self, query_terms: list[str], documents: list[list[str]]) -> list[float]:
        n = len(documents)
        average_length = sum(map(len, documents)) / n if n else 0.0
        document_frequency: Counter[str] = Counter()
        for tokens in documents:
            document_frequency.update(set(tokens))

        scores = []
        for tokens in documents:
            frequencies = Counter(tokens)
            length = len(tokens)
            score = 0.0
            for term in query_terms:
                frequency = frequencies.get(term, 0)
                if not frequency:
                    continue
                idf = math.log(1 + (n - document_frequency[term] + 0.5) / (document_frequency[term] + 0.5))
                denominator = frequency + self.k1 * (1 - self.b + self.b * length / (average_length or 1))
                score += idf * frequency * (self.k1 + 1) / denominator
            scores.append(score)
        return scores

    @staticmethod
    def _rank_map(indices: list[int], scores: list[float]) -> dict[int, int]:
        ranks = {}
        rank = 0
        previous = None
        for position, index in enumerate(indices, start=1):
            if scores[index] != previous:
                rank = position
            ranks[index] = rank
            previous = scores[index]
        return ranks
