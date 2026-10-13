import hashlib
import re
import time
import logging
from pydantic import ValidationError

from app.config import Settings
from app.embeddings import HashingEmbeddingProvider
from app.providers import ChatProvider, ChatProviderError
from app.diagnostics import request_trace_id
from app.relevance import has_relevant_evidence, query_focus
from app.retrieval import Chunk, RetrievalEngine, evidence_passages, passage_text
from app.schemas import Citation, CoverageReview, GenerateRequest, GenerateResponse, GroundedOutput, IndexDocumentRequest, SearchRequest, SearchResponse


REFUSAL_TEXT = "当前知识库没有足够依据回答该问题"


class RagService:
    def __init__(self, settings: Settings, store):
        self.settings = settings
        self.store = store
        self.embedding = HashingEmbeddingProvider(settings.embedding_dimensions)
        self.retrieval = RetrievalEngine(store, self.embedding, settings.bm25_k1, settings.bm25_b)
        self.chat = ChatProvider(
            settings.chat_provider, settings.chat_base_url, settings.chat_api_key, settings.chat_model,
            settings.chat_timeout_seconds
        )

    def index_document(self, knowledge_base_id: str, request: IndexDocumentRequest) -> int:
        chunks = []
        for index, content in enumerate(request.chunks):
            cleaned = content.strip()
            if not cleaned:
                continue
            digest = hashlib.sha256(
                f"{knowledge_base_id}:{request.document_version_id}:{index}".encode("utf-8")
            ).hexdigest()
            chunks.append(
                Chunk(
                    chunk_id=digest,
                    knowledge_base_id=knowledge_base_id,
                    document_version_id=request.document_version_id,
                    document_name=request.document_name,
                    chunk_no=index,
                    content=cleaned,
                    vector=self.embedding.embed(cleaned),
                )
            )
        if not chunks:
            raise ValueError("No non-empty chunks were provided")
        self.store.replace_version(request.document_version_id, chunks)
        return len(chunks)

    def search(self, request: SearchRequest) -> SearchResponse:
        with self.store.read_snapshot():
            return self._search(request)

    def _search(self, request: SearchRequest) -> SearchResponse:
        started = time.perf_counter()
        evidence = self.retrieval.search(request)
        sufficient = any(self._usable_evidence(request.query, item, request.method,
                                               self.settings.min_evidence_score) for item in evidence)
        return SearchResponse(
            query=request.query,
            method=request.method,
            evidence=evidence,
            sufficient=sufficient,
            reason="SUFFICIENT_EVIDENCE" if sufficient else "NO_MATCHING_EVIDENCE",
            latency_ms=round((time.perf_counter() - started) * 1000),
        )

    def generate(self, request: GenerateRequest) -> GenerateResponse:
        with self.store.read_snapshot():
            return self._generate(request)

    def _generate(self, request: GenerateRequest) -> GenerateResponse:
        started = time.perf_counter()
        if request.answer_mode == "general":
            answer = self.chat.generate_general(request.question)
            # General knowledge is explicitly ungrounded. Numeric text, lists, and
            # array indices are not KB citations; never synthesize verified sources.
            return GenerateResponse(status="ANSWERED",
                answer="通用知识回答（非知识库来源，未联网核验）：\n\n" + answer,
                citations=[], query=request.question, evidence_count=0,
                latency_ms=round((time.perf_counter() - started) * 1000),
                evidence_reason="USER_REQUESTED_GENERAL", model=self.settings.chat_model,
                answer_origin="GENERAL", evidence_state="NOT_APPLICABLE")
        query = self._rewrite_query(request.question, request.history)
        search = SearchRequest(
            query=query,
            knowledge_base_id=request.knowledge_base_id,
            knowledge_base_ids=request.knowledge_base_ids,
            top_k=request.top_k,
            method=request.method,
            document_version_ids=request.document_version_ids,
            rrf_k=request.rrf_k,
            vector_weight=request.vector_weight,
            bm25_weight=request.bm25_weight,
        )
        evidence = self.retrieval.search(search)
        if not evidence:
            return self._refused(query, started, "NO_MATCHING_EVIDENCE")

        # RRF is a rank fusion score, not a calibrated confidence score.
        # This gate remains a hashing-baseline heuristic, pending real-model calibration.
        threshold = request.retrieval_threshold if request.method == "VECTOR" else request.evidence_threshold
        content_hits = [item for item in evidence if self._usable_evidence(query, item, request.method, threshold)]
        if not content_hits:
            return self._refused(query, started, "INSUFFICIENT_EVIDENCE")

        selected = content_hits[: min(request.top_k, 5)]
        mode = request.answer_mode
        if mode == "default":
            mode = self.settings.answer_mode or ("extractive" if self.settings.chat_provider.lower() == "extractive" else "generated")
        extractive = mode == "extractive"
        anchors = selected
        if not extractive:
            selected = self.retrieval.expand_context(search, selected)
            if not selected:
                return self._refused(query, started, "NO_MATCHING_EVIDENCE")
        passages = [[item] for item in selected] if extractive else evidence_passages(selected, anchors)
        evidence_payload = [{"document_name": passage[0].document_name, "content": passage_text(passage)}
                            for passage in passages]
        source_numbers = {item.chunk_id: index for index, item in enumerate(selected, start=1)}
        passage_numbers = [[source_numbers[item.chunk_id] for item in passage] for passage in passages]
        evidence_state = "UNASSESSED"
        used = set()
        if extractive:
            answer = "原文检索结果（未调用生成模型）：\n\n" + "\n\n".join(
                f"[{index}] {item['document_name']}\n{item['content']}"
                for index, item in enumerate(evidence_payload, start=1)
            )
        else:
            raw = self.chat.generate(query, evidence_payload, [])
            if raw.strip().rstrip("。.!！") == REFUSAL_TEXT:
                return self._refused(query, started, "MODEL_EVIDENCE_INSUFFICIENT", self.settings.chat_model, len(selected))
            grounded = self._validate_grounded(raw, len(passages), evidence_payload)
            # Review only a valid partial/insufficient draft, using the same
            # question and evidence. Never force complete or weaken citation checks.
            remaining = self.settings.chat_timeout_seconds - (time.perf_counter() - started)
            focus = query_focus(query)
            requirements = [f"{subject}的{facet}" for subject in focus[0] for facet in focus[1]] if focus else []
            if (grounded.coverage != "complete" and 1 <= len(requirements) <= 12 and isinstance(self.chat, ChatProvider)
                    and self.chat.enabled and remaining >= 1):
                try:
                    reviewed = self.chat.review_coverage(query, evidence_payload, requirements, remaining)
                    grounded = self._validate_coverage_review(reviewed, requirements, evidence_payload)
                except ChatProviderError as error:
                    logging.getLogger('uvicorn.error').warning(
                        'coverage_review_fallback trace=%s code=%s reason=%s',
                        request_trace_id.get(), error.code, error.reason)
                    # Optional review failure preserves the valid conservative draft.
            if grounded.coverage == "insufficient":
                return self._refused(query, started, "MODEL_EVIDENCE_INSUFFICIENT", self.settings.chat_model, len(selected))
            for claim in grounded.claims:
                # Model IDs identify continuous passages; expose all constituent
                # original chunks, never falsely label joined text as one quote.
                claim.citations = list(dict.fromkeys(number for n in claim.citations for number in passage_numbers[n - 1]))
                used.update(claim.citations)
            answer = "\n\n".join(claim.text + " " + "".join(f"[{n}]" for n in dict.fromkeys(claim.citations)) for claim in grounded.claims)
            evidence_state = "MODEL_REPORTED_COMPLETE"
            if grounded.coverage == "partial":
                evidence_state = "MODEL_REPORTED_PARTIAL"
                answer = "资料仅支持部分问题，以下为有依据的内容：\n\n" + answer
                answer += "\n\n资料未覆盖的部分（模型判断，需核对）：\n" + "\n".join("- " + item for item in grounded.missing_aspects)

        citations = [
            Citation(
                number=index,
                chunk_id=item.chunk_id,
                document_version_id=item.document_version_id,
                document_name=item.document_name,
                chunk_no=item.chunk_no,
                quote=item.content,
            )
            for index, item in enumerate(selected, start=1)
        ]
        if not extractive:
            citations = [citation for citation in citations if citation.number in used]
        return GenerateResponse(
            status="ANSWERED",
            answer=answer,
            citations=citations,
            query=query,
            evidence_count=len(selected),
            latency_ms=round((time.perf_counter() - started) * 1000),
            evidence_reason="EXTRACTIVE_EVIDENCE" if extractive else "SUFFICIENT_EVIDENCE",
            model="extractive-no-llm" if extractive else self.settings.chat_model,
            answer_origin="KNOWLEDGE_BASE", evidence_state=evidence_state,
        )

    @classmethod
    def _validate_grounded(cls, raw: str, evidence_count: int, evidence: list[dict] | None = None) -> GroundedOutput:
        try:
            grounded = GroundedOutput.model_validate_json(raw)
        except ValidationError as error:
            raise ChatProviderError("Model coverage output is invalid", "CHAT_MODEL_INVALID_RESPONSE") from error
        if any(cls._has_prose_citation(item) for item in grounded.missing_aspects):
            raise ChatProviderError("Missing aspects cannot contain citations", "CHAT_MODEL_INVALID_CITATION")
        for claim in grounded.claims:
            if evidence:
                claim.text = cls._format_source_array_accesses(claim.text, evidence)
            if (not claim.text.strip() or cls._has_prose_citation(claim.text)
                    or any(n < 1 or n > evidence_count for n in claim.citations)):
                raise ChatProviderError("Model claim has invalid citations", "CHAT_MODEL_INVALID_CITATION")
        return grounded

    @classmethod
    def _validate_coverage_review(cls, raw: str, requirements: list[str], evidence: list[dict]) -> GroundedOutput:
        try:
            review = CoverageReview.model_validate_json(raw)
        except ValidationError as error:
            raise ChatProviderError("Invalid coverage audit", "CHAT_MODEL_INVALID_RESPONSE") from error
        ids = [aspect.id for aspect in review.aspects]
        if sorted(ids) != list(range(1, len(requirements) + 1)):
            raise ChatProviderError("Coverage audit omitted or duplicated requirements", "CHAT_MODEL_INVALID_RESPONSE")
        claims, missing = [], []
        for aspect in sorted(review.aspects, key=lambda item: item.id):
            if aspect.status == "insufficient":
                missing.append(requirements[aspect.id - 1])
                continue
            for citation in aspect.evidence:
                if not 1 <= citation.source <= len(evidence):
                    raise ChatProviderError("Invalid coverage source", "CHAT_MODEL_INVALID_CITATION")
                quote = " ".join(citation.quote.split())
                original = " ".join(evidence[citation.source - 1]["content"].split())
                if len(quote) < 8 or quote not in original:
                    raise ChatProviderError("Coverage quote is not in the source", "CHAT_MODEL_INVALID_CITATION")
            claims.append({"text": aspect.text, "citations": list(dict.fromkeys(c.source for c in aspect.evidence))})
        grounded = GroundedOutput(coverage="insufficient" if not claims else "partial" if missing else "complete",
                                  claims=claims, missing_aspects=missing)
        return cls._validate_grounded(grounded.model_dump_json(), len(evidence), evidence)

    @classmethod
    def _format_source_array_accesses(cls, text: str, evidence: list[dict]) -> str:
        """Format unmarked member-array expressions actually present in sources.

        Keep bare prose references ambiguous/fail-closed. A dotted identifier plus
        an index (e.g. u.keys[0]) copied from a source is code, not a source ID.
        Mark it as code for both backend validation and frontend rendering.
        """
        originals = []
        for item in evidence:
            original = item['content']
            # ODS wraps identifiers as \ensuremath{\mathtt{u.keys}}[0].
            # Remove only these display wrappers for comparison, never indices,
            # operators, negations, or the source returned in citations.
            while True:
                plain = re.sub(r"\\(?:ensuremath|mathtt)\{([^{}]*)\}", r"\1", original)
                if plain == original:
                    break
                original = plain
            originals.append(original)
        prose = cls._mask_markdown_code(text)
        pattern = r"(?<![\w.])[A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)+\[\d+\]"
        for match in reversed(list(re.finditer(pattern, prose))):
            expression = match.group()
            source_pattern = r"(?<![\w.])" + re.escape(expression)
            if any(re.search(source_pattern, original) for original in originals):
                text = text[:match.start()] + '`' + expression + '`' + text[match.end():]
        return text

    @staticmethod
    def _rewrite_query(question: str, history: list[dict[str, str]]) -> str:
        question = question.strip()
        pronoun = r"^(?:它们?|这个|那个|这种|那种|两者|二者)(?:的|有|是|能|如何|怎么|在|与|和|之间|呢|吗|\s)"
        if history and len(question) < 40 and re.match(pronoun, question):
            previous = next((item.get("content", "").strip() for item in reversed(history)
                             if item.get("role") == "user" and item.get("content")
                             and not re.match(pronoun, item["content"].strip())), "")
            if previous:
                return f"{previous[:160]}，{question}"
        return question

    @staticmethod
    def _has_query_overlap(query: str, content: str) -> bool:
        return has_relevant_evidence(query, content)

    @staticmethod
    def _usable_evidence(query, item, method: str, threshold: float) -> bool:
        # Hybrid retrieval accepts either channel after the lexical topic gate.
        # A low hashing score cannot veto substantive BM25 evidence; this is not
        # a calibrated confidence estimate or a semantic support guarantee.
        return has_relevant_evidence(query, item.content) and (
            item.vector_score >= threshold or (method != "VECTOR" and item.bm25_score > 0)
        )

    @classmethod
    def _has_prose_citation(cls, text: str) -> bool:
        return bool(re.search(r"\[\d+\]", cls._mask_markdown_code(text)))

    @staticmethod
    def _mask_markdown_code(text: str) -> str:
        """Detect numeric reference markers outside explicitly delimited code.

        Code such as arr[0] is not a citation. Inspect a masked copy only: the
        model's code is returned unchanged, and structured IDs are still checked.
        Unclosed blocks/spans remain subject to validation, never hide the rest.
        """
        lines = text.splitlines(keepends=True)
        index = 0
        while index < len(lines):
            opening = re.match(r"^ {0,3}(`{3,}|~{3,})([^\r\n]*)", lines[index])
            if not opening or (opening[1][0] == "`" and "`" in opening[2]):
                index += 1
                continue
            fence = opening[1]
            closing = re.compile(r"^ {0,3}" + re.escape(fence[0]) +
                                 "{" + str(len(fence)) + r",}[ \t]*(?:\r?\n)?$")
            end = next((j for j in range(index + 1, len(lines)) if closing.match(lines[j])), None)
            if end is None:
                index += 1
                continue
            for j in range(index, end + 1):
                lines[j] = re.sub(r"[^\r\n]", " ", lines[j])
            index = end + 1
        prose = "".join(lines)
        # Backtick runs must match exactly; a single tick cannot close a double span.
        return re.sub(r"(?<!`)(`+)(?!`)[\s\S]*?(?<!`)\1(?!`)",
                      lambda match: re.sub(r"[^\r\n]", " ", match.group()), prose)

    @staticmethod
    def _keep_valid_citations(answer: str, citation_count: int) -> str:
        def replace(match: re.Match[str]) -> str:
            number = int(match.group(1))
            return match.group(0) if 1 <= number <= citation_count else ""

        return re.sub(r"\[(\d+)\]", replace, answer).strip()

    @staticmethod
    def _refused(query: str, started: float, reason: str, model: str = "disabled", evidence_count: int = 0) -> GenerateResponse:
        return GenerateResponse(
            status="REFUSED",
            answer=REFUSAL_TEXT,
            citations=[],
            query=query,
            evidence_count=evidence_count,
            latency_ms=round((time.perf_counter() - started) * 1000),
            evidence_reason=reason,
            model=model,
            answer_origin="NONE", evidence_state="INSUFFICIENT",
        )
