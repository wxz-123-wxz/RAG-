package com.graduation.ragqa.conversation;

import com.graduation.ragqa.config.RagProperties;
import com.graduation.ragqa.document.DocumentMapper;
import com.graduation.ragqa.knowledgebase.*;
import com.graduation.ragqa.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationServiceTest {
    ConversationMapper conversations = mock(ConversationMapper.class);
    ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    CitationMapper citations = mock(CitationMapper.class);
    KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    RagAnswerClient rag = mock(RagAnswerClient.class);
    DocumentMapper documents = mock(DocumentMapper.class);
    KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    UserPrincipal user = new UserPrincipal(1L, "test", "Test", "unused", "ACTIVE", Set.of("STUDENT"));
    KnowledgeBaseRow kb = new KnowledgeBaseRow();
    ConversationService service;

    @BeforeEach void setup() {
        var conversation = new ConversationRow(); conversation.setId(2L); conversation.setKnowledgeBaseId(3L);
        when(conversations.findForOwner(2L, 1L)).thenReturn(Optional.of(conversation));
        kb.setStatus("PUBLISHED"); when(access.requireVisible(3L, 1L)).thenReturn(kb);
        when(conversations.publishedVersionIdStrings(2L)).thenReturn(List.of("4"));
        when(messages.recentUserMessages(2L)).thenReturn(List.of());
        when(citations.findByMessage(anyLong())).thenReturn(List.of());
        service = new ConversationService(conversations, messages, citations, bases,
                access, rag, new RagProperties("http://localhost", "", Duration.ofSeconds(1)), documents);
    }

    @Test void questionUsesNonNullVersion() {
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("REFUSED", "依据不足", List.of(), "q", 0, 1, "NO_MATCHING_EVIDENCE", "disabled"));
        service.ask(2L, new AskRequest("q"), user);
        verify(messages).insert(anyLong(), eq(2L), eq("USER"), eq("q"), isNull(), eq(1), isNull());
    }

    @Test void offlineKnowledgeBaseCannotRegenerate() {
        var question = new MessageRow(); question.setConversationId(2L); question.setContent("q");
        when(messages.findQuestionById(9L)).thenReturn(Optional.of(question));
        kb.setStatus("OFFLINE");
        assertThatThrownBy(() -> service.regenerate(9L, user)).hasMessageContaining("下架");
        verifyNoInteractions(rag);
    }

    @Test void citationOutsideAllowedVersionsIsRejected() {
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("ANSWERED", "answer [1]",
                List.of(new RagAnswerClient.Citation(1, "hash", "999", "foreign", 0, "quote")), "q", 1, 1, "SUFFICIENT_EVIDENCE", "test"));
        assertThatThrownBy(() -> service.ask(2L, new AskRequest("q"), user)).hasMessageContaining("无法验证的引用");
        verifyNoInteractions(documents);
        verify(citations, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void alteredQuoteIsRejected() {
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("ANSWERED", "answer [1]",
                List.of(new RagAnswerClient.Citation(1, "hash", "4", "source", 0, "tampered")), "q", 1, 1, "SUFFICIENT_EVIDENCE", "test"));
        when(documents.findChunkIdByNumber(4L, 0)).thenReturn(Optional.of(5L));
        when(documents.findChunkContent(5L)).thenReturn(Optional.of("original"));
        assertThatThrownBy(() -> service.ask(2L, new AskRequest("q"), user)).hasMessageContaining("无法验证的引用");
        verify(citations, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private MessageRow fallbackSource(String status, String state) {
        var source = new MessageRow(); source.setId(10L); source.setConversationId(2L);
        source.setParentMessageId(9L); source.setRole("ASSISTANT"); source.setAnswerStatus(status);
        source.setAnswerOrigin("REFUSED".equals(status) ? "NONE" : "KNOWLEDGE_BASE"); source.setEvidenceState(state);
        when(messages.findConversationId(10L)).thenReturn(Optional.of(2L));
        when(messages.findById(10L, 2L)).thenReturn(Optional.of(source));
        var question = new MessageRow(); question.setId(9L); question.setConversationId(2L); question.setContent("original question");
        when(messages.findQuestionById(9L)).thenReturn(Optional.of(question));
        return source;
    }

    @ParameterizedTest
    @CsvSource({"REFUSED,INSUFFICIENT", "ANSWERED,MODEL_REPORTED_PARTIAL",
                "ANSWERED,MODEL_REPORTED_COMPLETE", "ANSWERED,UNASSESSED"})
    void independentAnswerAcceptsEverySavedKnowledgeAnswerAndSendsOnlyOriginalQuestion(String status, String state) {
        fallbackSource(status, state);
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("ANSWERED", "general answer",
                List.of(), "original question", 0, 1, "USER_REQUESTED_GENERAL", "test", "GENERAL", "NOT_APPLICABLE"));
        var answer = service.generalAnswer(10L, user);
        assertThat(answer.assistantMessage().answerOrigin()).isEqualTo("GENERAL");
        assertThat(answer.assistantMessage().citations()).isEmpty();
        verify(rag).answer(argThat(request -> "general".equals(request.answerMode())
                && request.history().isEmpty() && request.documentVersionIds().isEmpty()
                && "original question".equals(request.question())));
        verify(messages).saveAnswerMetadata(anyLong(), eq("test"), eq("GENERAL"), eq("NOT_APPLICABLE"));
        verifyNoInteractions(documents);
    }

    @ParameterizedTest
    @CsvSource({"USER,KNOWLEDGE_BASE,ANSWERED", "ASSISTANT,GENERAL,ANSWERED", "ASSISTANT,KNOWLEDGE_BASE,FAILED"})
    void independentAnswerRejectsUserGeneralAndFailedMessages(String role, String origin, String status) {
        var source = fallbackSource(status, "MODEL_REPORTED_COMPLETE");
        source.setRole(role);
        source.setAnswerOrigin(origin);
        assertThatThrownBy(() -> service.generalAnswer(10L, user)).hasMessageContaining("仅已保存的知识库回答");
        verifyNoInteractions(rag);
    }

    @Test void independentAnswerRequiresOriginalQuestionLink() {
        var source = fallbackSource("ANSWERED", "MODEL_REPORTED_COMPLETE");
        source.setParentMessageId(null);
        assertThatThrownBy(() -> service.generalAnswer(10L, user)).hasMessageContaining("仅已保存的知识库回答");
        verifyNoInteractions(rag);
    }

    @Test void fallbackCannotInventKnowledgeBaseCitations() {
        fallbackSource("REFUSED", "INSUFFICIENT");
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("ANSWERED", "general [1]",
                List.of(new RagAnswerClient.Citation(1, "hash", "4", "source", 0, "quote")),
                "q", 0, 1, "USER_REQUESTED_GENERAL", "test", "GENERAL", "NOT_APPLICABLE"));
        assertThatThrownBy(() -> service.generalAnswer(10L, user)).hasMessageContaining("不得携带知识库引用");
        verify(messages, never()).insertAnswer(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void normalRagCannotSilentlyReturnGeneralAnswer() {
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("ANSWERED", "general answer",
                List.of(), "q", 0, 1, "USER_REQUESTED_GENERAL", "test", "GENERAL", "NOT_APPLICABLE"));
        assertThatThrownBy(() -> service.ask(2L, new AskRequest("q"), user)).hasMessageContaining("来源或依据状态无效");
    }

    @Test void fallbackRequiresConversationOwnership() {
        fallbackSource("REFUSED", "INSUFFICIENT");
        when(conversations.findForOwner(2L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generalAnswer(10L, user)).hasMessageContaining("无权访问");
        verifyNoInteractions(rag);
    }

    @Test void regenerationCannotBypassExplicitFallbackEndpoint() {
        assertThatThrownBy(() -> service.regenerate(9L, user, "general")).hasMessageContaining("回答方式无效");
        verifyNoInteractions(rag);
    }

    @Test void partialAnswerOffersFallbackAndOfflineKnowledgeBaseStillBlocksIt() {
        fallbackSource("ANSWERED", "MODEL_REPORTED_PARTIAL");
        kb.setStatus("OFFLINE");
        assertThatThrownBy(() -> service.generalAnswer(10L, user)).hasMessageContaining("下架");
        verifyNoInteractions(rag);
    }

    private void allScope() {
        var conversation = new ConversationRow(); conversation.setId(2L); conversation.setKnowledgeBaseId(null);
        when(conversations.findForOwner(2L, 1L)).thenReturn(Optional.of(conversation));
        kb.setId(3L);
        var another = new KnowledgeBaseRow(); another.setId(5L); another.setStatus("PUBLISHED");
        var offline = new KnowledgeBaseRow(); offline.setId(6L); offline.setStatus("OFFLINE");
        when(bases.findVisible(1L, false)).thenReturn(List.of(kb, another, offline));
        when(conversations.publishedVersionsForBases(List.of(3L, 5L))).thenReturn(List.of("4", "7"));
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("REFUSED", "依据不足", List.of(), "q", 0, 1, "NO_MATCHING_EVIDENCE", "disabled"));
    }

    @Test void allScopeIncludesOnlyVisiblePublishedBasesAndVersions() {
        allScope();
        service.ask(2L, new AskRequest("q"), user);
        verify(rag).answer(argThat(r -> r.knowledgeBaseId().equals("all")
                && r.knowledgeBaseIds().equals(List.of("3", "5")) && r.documentVersionIds().equals(List.of("4", "7"))));
    }

    @Test void allScopeReevaluatesPermissionsForEachQuestion() {
        allScope();
        service.ask(2L, new AskRequest("first"), user);
        when(bases.findVisible(1L, false)).thenReturn(List.of(kb));
        when(conversations.publishedVersionsForBases(List.of(3L))).thenReturn(List.of("4"));
        service.ask(2L, new AskRequest("second"), user);
        verify(rag).answer(argThat(r -> r.question().equals("second")
                && r.knowledgeBaseIds().equals(List.of("3")) && r.documentVersionIds().equals(List.of("4"))));
    }

    @Test void allScopeCannotReturnAnUnauthorizedCitation() {
        allScope();
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("ANSWERED", "answer [1]",
                List.of(new RagAnswerClient.Citation(1, "hash", "999", "private", 0, "quote")), "q", 1, 1, "SUFFICIENT_EVIDENCE", "test"));
        assertThatThrownBy(() -> service.ask(2L, new AskRequest("q"), user)).hasMessageContaining("无法验证的引用");
        verifyNoInteractions(documents);
    }

    @Test void allScopeWithNoAccessibleBasesDoesNotCallRag() {
        allScope();
        when(bases.findVisible(1L, false)).thenReturn(List.of());
        assertThatThrownBy(() -> service.ask(2L, new AskRequest("q"), user)).hasMessageContaining("暂无可访问");
        verifyNoInteractions(rag);
    }

    @Test void allScopeRegenerationRechecksAllowedVersions() {
        allScope();
        var question = new MessageRow(); question.setConversationId(2L); question.setContent("q");
        when(messages.findQuestionById(9L)).thenReturn(Optional.of(question));
        service.regenerate(9L, user, "generated");
        verify(rag).answer(argThat(r -> r.knowledgeBaseIds().equals(List.of("3", "5"))
                && r.answerMode().equals("generated") && r.documentVersionIds().equals(List.of("4", "7"))));
    }

    @Test void allScopeGeneralAnswerStillSendsNoDocuments() {
        allScope();
        fallbackSource("REFUSED", "INSUFFICIENT");
        when(rag.answer(any())).thenReturn(new RagAnswerClient.RagAnswerResult("ANSWERED", "general",
                List.of(), "q", 0, 1, "USER_REQUESTED_GENERAL", "test", "GENERAL", "NOT_APPLICABLE"));
        service.generalAnswer(10L, user);
        verify(rag).answer(argThat(r -> r.answerMode().equals("general") && r.documentVersionIds().isEmpty()
                && r.knowledgeBaseIds().isEmpty() && r.history().isEmpty()));
    }
}
