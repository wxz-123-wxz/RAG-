package com.graduation.ragqa.conversation;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.config.RagProperties;
import com.graduation.ragqa.document.DocumentMapper;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseAccessService;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseRow;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseMapper;
import com.graduation.ragqa.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final CitationMapper citationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseAccessService accessService;
    private final RagAnswerClient ragClient;
    private final RagProperties ragProperties;
    private final DocumentMapper documentMapper;

    public ConversationService(ConversationMapper conversationMapper,
                               ConversationMessageMapper messageMapper,
                               CitationMapper citationMapper,
                               KnowledgeBaseMapper knowledgeBaseMapper,
                               KnowledgeBaseAccessService accessService,
                               RagAnswerClient ragClient,
                               RagProperties ragProperties,
                               DocumentMapper documentMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.citationMapper = citationMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.accessService = accessService;
        this.ragClient = ragClient;
        this.ragProperties = ragProperties;
        this.documentMapper = documentMapper;
    }

    public List<ConversationRow> list(Long userId) {
        return conversationMapper.findForUser(userId);
    }

    @Transactional
    public ConversationRow create(ConversationRequest request, UserPrincipal user) {
        Long kbId = null;
        if (!"all".equals(request.knowledgeBaseId())) {
            try {
                kbId = Long.parseLong(request.knowledgeBaseId());
            } catch (NumberFormatException exception) {
                throw new BusinessException("VALIDATION_ERROR", "知识库编号无效", HttpStatus.BAD_REQUEST);
            }
            KnowledgeBaseRow knowledgeBase = accessService.requireVisible(kbId, user.id());
            if (!"PUBLISHED".equals(knowledgeBase.getStatus())) {
                throw new BusinessException("KNOWLEDGE_BASE_UNAVAILABLE", "请选择已发布的知识库", HttpStatus.CONFLICT);
            }
        } else {
            visiblePublishedBaseIds(user);
        }
        long id = IdWorker.getId();
        String title = request.title() == null || request.title().isBlank()
                ? "新对话" : request.title().trim();
        conversationMapper.insert(id, user.id(), kbId, title);
        return conversationMapper.findForOwner(id, user.id()).orElseThrow();
    }

    public ConversationDetail get(Long conversationId, UserPrincipal user) {
        ConversationRow conversation = requireOwner(conversationId, user.id());
        return new ConversationDetail(conversation, messageMapper.findByConversation(conversationId));
    }

    @Transactional
    public ConversationRow rename(Long conversationId, RenameConversationRequest request, UserPrincipal user) {
        requireOwner(conversationId, user.id());
        conversationMapper.rename(conversationId, user.id(), request.title().trim());
        return requireOwner(conversationId, user.id());
    }

    @Transactional
    public void delete(Long conversationId, UserPrincipal user) {
        requireOwner(conversationId, user.id());
        conversationMapper.softDelete(conversationId, user.id());
    }

    @Transactional
    public AnswerResult ask(Long conversationId, AskRequest request, UserPrincipal user) {
        ConversationRow conversation = requireOwner(conversationId, user.id());
        RetrievalScope scope = resolveScope(conversation, user);

        Long userMessageId = IdWorker.getId();
        messageMapper.insert(userMessageId, conversationId, "USER", request.content().trim(), null, 1, null);
        List<RagAnswerClient.HistoryEntry> history = messageMapper.recentUserMessages(conversationId).stream()
                .filter(content -> !content.equals(request.content().trim()))
                .limit(10)
                .map(content -> new RagAnswerClient.HistoryEntry("user", content))
                .toList();
        List<String> allowedVersions = scope.versions();
        RagAnswerClient.RagAnswerResult rag = ragClient.answer(new RagAnswerClient.RagAnswerRequest(
                request.content().trim(), request.content().trim(), scope.primary(),
                allowedVersions, 5, "HYBRID", 60,
                1.0, 1.0, history, 0.12, 0.05, request.normalizedAnswerMode(), scope.ids()));
        if (rag == null || rag.status() == null || rag.answer() == null) {
            throw new BusinessException("RAG_SERVICE_ERROR", "问答服务返回了无效结果", HttpStatus.BAD_GATEWAY);
        }

        int latency = rag.latencyMs() == null ? 0 : Math.max(rag.latencyMs(), 0);
        Long answerId = IdWorker.getId();
        String status = "REFUSED".equals(rag.status()) ? "REFUSED" : "ANSWERED";
        messageMapper.insertAnswer(answerId, conversationId, userMessageId, rag.answer(), status, 1, latency);
        saveMetadata(answerId, rag, false);
        saveCitations(answerId, rag.citations(), allowedVersions);
        conversationMapper.touch(conversationId);
        return new AnswerResult(userMessageId.toString(),
                new MessageResponse(answerId.toString(), "ASSISTANT", rag.answer(), status,
                        latency, citationMapper.findByMessage(answerId), rag.model(), rag.answerOrigin(), rag.evidenceState()));
    }

    @Transactional
    public AnswerResult regenerate(Long questionMessageId, UserPrincipal user) {
        return regenerate(questionMessageId, user, "default");
    }

    @Transactional
    public AnswerResult regenerate(Long questionMessageId, UserPrincipal user, String answerMode) {
        if (!List.of("default", "extractive", "generated").contains(answerMode)) {
            throw new BusinessException("VALIDATION_ERROR", "回答方式无效", HttpStatus.BAD_REQUEST);
        }
        MessageRow question = messageMapper.findQuestionById(questionMessageId).orElseThrow(this::notFound);
        ConversationRow conversation = requireOwner(question.getConversationId(), user.id());
        RetrievalScope scope = resolveScope(conversation, user);
        List<String> allowedVersions = scope.versions();
        String content = question.getContent();
        int version = messageMapper.maxAnswerVersion(questionMessageId) + 1;
        long started = System.nanoTime();
        RagAnswerClient.RagAnswerResult rag = ragClient.answer(new RagAnswerClient.RagAnswerRequest(
                content, content, scope.primary(),
                allowedVersions, 5,
                "HYBRID", 60, 1.0, 1.0, List.of(), 0.12, 0.05, answerMode, scope.ids()));
        if (rag == null || rag.status() == null || rag.answer() == null) {
            throw new BusinessException("RAG_SERVICE_ERROR", "问答服务返回了无效结果", HttpStatus.BAD_GATEWAY);
        }
        Long answerId = IdWorker.getId();
        int latency = (int) Math.min((System.nanoTime() - started) / 1_000_000, Integer.MAX_VALUE);
        String status = "REFUSED".equals(rag.status()) ? "REFUSED" : "ANSWERED";
        messageMapper.insertAnswer(answerId, conversation.getId(), questionMessageId,
                rag.answer(), status, version, latency);
        saveMetadata(answerId, rag, false);
        saveCitations(answerId, rag.citations(), allowedVersions);
        return new AnswerResult(questionMessageId.toString(),
                new MessageResponse(answerId.toString(), "ASSISTANT", rag.answer(), status,
                        latency, citationMapper.findByMessage(answerId.longValue()), rag.model(), rag.answerOrigin(), rag.evidenceState()));
    }

    @Transactional
    public AnswerResult generalAnswer(Long sourceAnswerId, UserPrincipal user) {
        MessageRow source = requireMessageOwner(sourceAnswerId, user.id());
        if (!"ASSISTANT".equals(source.getRole()) || "GENERAL".equals(source.getAnswerOrigin())
                || source.getParentMessageId() == null
                || !("REFUSED".equals(source.getAnswerStatus()) || "ANSWERED".equals(source.getAnswerStatus()))) {
            throw new BusinessException("FALLBACK_NOT_AVAILABLE", "仅已保存的知识库回答可以让大模型独立回答", HttpStatus.CONFLICT);
        }
        ConversationRow conversation = requireOwner(source.getConversationId(), user.id());
        RetrievalScope scope = resolveScope(conversation, user);
        MessageRow question = messageMapper.findQuestionById(source.getParentMessageId()).orElseThrow(this::notFound);
        if (!conversation.getId().equals(question.getConversationId())) throw notFound();
        RagAnswerClient.RagAnswerResult rag = ragClient.answer(new RagAnswerClient.RagAnswerRequest(
                question.getContent(), question.getContent(), scope.primary(),
                List.of(), 5, "HYBRID", 60, 1.0, 1.0, List.of(), 0.12, 0.05, "general"));
        if (rag == null || !"ANSWERED".equals(rag.status()) || rag.answer() == null
                || rag.citations() == null || !rag.citations().isEmpty()) {
            throw new BusinessException("RAG_SERVICE_ERROR", "通用回答不得携带知识库引用", HttpStatus.BAD_GATEWAY);
        }
        Long answerId = IdWorker.getId();
        int latency = rag.latencyMs() == null ? 0 : Math.max(0, rag.latencyMs());
        messageMapper.insertAnswer(answerId, conversation.getId(), question.getId(), rag.answer(), "ANSWERED",
                messageMapper.maxAnswerVersion(question.getId()) + 1, latency);
        saveMetadata(answerId, rag, true);
        conversationMapper.touch(conversation.getId());
        return new AnswerResult(question.getId().toString(), new MessageResponse(answerId.toString(),
                "ASSISTANT", rag.answer(), "ANSWERED", latency, List.of(), rag.model(), rag.answerOrigin(), rag.evidenceState()));
    }

    private void saveMetadata(Long answerId, RagAnswerClient.RagAnswerResult rag, boolean general) {
        boolean valid = general
                ? "GENERAL".equals(rag.answerOrigin()) && "NOT_APPLICABLE".equals(rag.evidenceState())
                : "REFUSED".equals(rag.status())
                    ? "NONE".equals(rag.answerOrigin()) && "INSUFFICIENT".equals(rag.evidenceState())
                    : "ANSWERED".equals(rag.status()) && "KNOWLEDGE_BASE".equals(rag.answerOrigin())
                        && rag.evidenceState() != null
                        && List.of("UNASSESSED", "MODEL_REPORTED_COMPLETE", "MODEL_REPORTED_PARTIAL").contains(rag.evidenceState());
        if (!valid) throw new BusinessException("RAG_SERVICE_ERROR", "问答来源或依据状态无效", HttpStatus.BAD_GATEWAY);
        messageMapper.saveAnswerMetadata(answerId, rag.model(), rag.answerOrigin(), rag.evidenceState());
    }

    private List<Long> visiblePublishedBaseIds(UserPrincipal user) {
        List<Long> ids = knowledgeBaseMapper.findVisible(user.id(), accessService.isSystemAdmin(user.id())).stream()
                .filter(kb -> "PUBLISHED".equals(kb.getStatus())).map(KnowledgeBaseRow::getId).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException("KNOWLEDGE_BASE_UNAVAILABLE", "暂无可访问的已发布知识库", HttpStatus.CONFLICT);
        }
        if (ids.size() > 1000) {
            throw new BusinessException("RETRIEVAL_SCOPE_TOO_LARGE", "检索范围过大，请选择单个知识库", HttpStatus.BAD_REQUEST);
        }
        return ids;
    }

    private RetrievalScope resolveScope(ConversationRow conversation, UserPrincipal user) {
        if (conversation.getKnowledgeBaseId() != null) {
            KnowledgeBaseRow kb = accessService.requireVisible(conversation.getKnowledgeBaseId(), user.id());
            if (!"PUBLISHED".equals(kb.getStatus())) {
                throw new BusinessException("KNOWLEDGE_BASE_UNAVAILABLE", "知识库已下架，暂时不能提问或重新生成", HttpStatus.CONFLICT);
            }
            List<String> versions = conversationMapper.publishedVersionIdStrings(conversation.getId());
            checkVersionLimit(versions);
            return new RetrievalScope(conversation.getKnowledgeBaseId().toString(), List.of(), versions);
        }
        List<Long> ids = visiblePublishedBaseIds(user);
        List<String> versions = conversationMapper.publishedVersionsForBases(ids);
        checkVersionLimit(versions);
        return new RetrievalScope("all", ids.stream().map(String::valueOf).toList(), versions);
    }

    private void checkVersionLimit(List<String> versions) {
        if (versions.size() > 1000) {
            throw new BusinessException("RETRIEVAL_SCOPE_TOO_LARGE", "检索文档过多，请缩小知识库范围", HttpStatus.BAD_REQUEST);
        }
    }

    private record RetrievalScope(String primary, List<String> ids, List<String> versions) { }

    public List<CitationRow> citations(Long messageId, UserPrincipal user) {
        MessageRow message = requireMessageOwner(messageId, user.id());
        if (!"ASSISTANT".equals(message.getRole())) {
            throw new BusinessException("INVALID_MESSAGE", "该消息没有引用", HttpStatus.BAD_REQUEST);
        }
        return citationMapper.findByMessage(messageId);
    }

    public void requireOwnedMessage(Long messageId, UserPrincipal user) {
        requireMessageOwner(messageId, user.id());
    }

    private void saveCitations(Long answerId, List<RagAnswerClient.Citation> citations, List<String> allowedVersions) {
        if (citations == null) return;
        for (RagAnswerClient.Citation citation : citations) {
            try {
                if (!allowedVersions.contains(citation.documentVersionId())) {
                    throw new IllegalArgumentException("Citation is outside the authorized version set");
                }
                Long versionId = Long.parseLong(citation.documentVersionId());
                Long actualChunkId = documentMapper.findChunkIdByNumber(versionId, citation.chunkNo())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown citation chunk number"));
                String original = documentMapper.findChunkContent(actualChunkId).orElseThrow();
                if (!original.equals(citation.quote())) throw new IllegalArgumentException("Citation content mismatch");
                citationMapper.insert(IdWorker.getId(), answerId, actualChunkId, citation.number(),
                        original, documentMapper.findDocumentTitleByVersion(versionId).orElseThrow(),
                        documentMapper.findHeading(actualChunkId).orElse(null),
                        documentMapper.findPageStart(actualChunkId).orElse(null),
                        documentMapper.findPageEnd(actualChunkId).orElse(null), null);
            } catch (RuntimeException exception) {
                throw new BusinessException("RAG_INVALID_CITATION", "问答服务返回了无法验证的引用", HttpStatus.BAD_GATEWAY);
            }
        }
    }

    private MessageRow requireMessageOwner(Long messageId, Long userId) {
        MessageRow message = messageMapper.findById(messageId, findConversationForMessage(messageId))
                .orElseThrow(this::notFound);
        requireOwner(message.getConversationId(), userId);
        return message;
    }

    private Long findConversationForMessage(Long messageId) {
        return messageMapper.findConversationId(messageId).orElseThrow(this::notFound);
    }

    private ConversationRow requireOwner(Long conversationId, Long userId) {
        return conversationMapper.findForOwner(conversationId, userId).orElseThrow(this::notFound);
    }

    private BusinessException notFound() {
        return new BusinessException("RESOURCE_NOT_FOUND", "资源不存在或无权访问", HttpStatus.NOT_FOUND);
    }

    public record ConversationDetail(ConversationRow conversation, List<MessageRow> messages) { }
    public record MessageResponse(String id, String role, String content, String status,
                                  int latencyMs, List<CitationRow> citations, String model,
                                  String answerOrigin, String evidenceState) { }
    public record AnswerResult(String userMessageId, MessageResponse assistantMessage) { }
}
