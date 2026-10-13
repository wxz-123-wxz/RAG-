package com.graduation.ragqa.conversation;

import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ConversationController {

    private final ConversationService conversationService;
    private final FeedbackMapper feedbackMapper;
    private final ConversationMessageMapper messageMapper;

    public ConversationController(ConversationService conversationService,
                                   FeedbackMapper feedbackMapper,
                                   ConversationMessageMapper messageMapper) {
        this.conversationService = conversationService;
        this.feedbackMapper = feedbackMapper;
        this.messageMapper = messageMapper;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationRow>> list(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(conversationService.list(user.id()));
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationRow> create(@Valid @RequestBody ConversationRequest request,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(conversationService.create(request, user));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<ConversationService.ConversationDetail> get(@PathVariable Long conversationId,
                                                                   @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(conversationService.get(conversationId, user));
    }

    @PatchMapping("/conversations/{conversationId}")
    public ApiResponse<ConversationRow> rename(@PathVariable Long conversationId,
                                               @Valid @RequestBody RenameConversationRequest request,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(conversationService.rename(conversationId, request, user));
    }

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long conversationId, @AuthenticationPrincipal UserPrincipal user) {
        conversationService.delete(conversationId, user);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ApiResponse<ConversationService.AnswerResult> ask(@PathVariable Long conversationId,
                                                             @Valid @RequestBody AskRequest request,
                                                             @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(conversationService.ask(conversationId, request, user));
    }

    @PostMapping("/messages/{messageId}/regenerations")
    public ApiResponse<ConversationService.AnswerResult> regenerate(@PathVariable Long messageId,
                                                                     @Valid @RequestBody(required = false) RegenerateRequest request,
                                                                     @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(conversationService.regenerate(messageId, user,
                request == null ? "default" : request.normalizedAnswerMode()));
    }

    @GetMapping("/messages/{messageId}/citations")
    public ApiResponse<List<CitationRow>> citations(@PathVariable Long messageId,
                                                     @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(conversationService.citations(messageId, user));
    }

    @PostMapping("/messages/{messageId}/general-answer")
    public ApiResponse<ConversationService.AnswerResult> generalAnswer(@PathVariable Long messageId,
            @Valid @RequestBody GeneralAnswerRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(conversationService.generalAnswer(messageId, user));
    }

    @PutMapping("/messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void feedback(@PathVariable Long messageId,
                         @Valid @RequestBody FeedbackRequest request,
                         @AuthenticationPrincipal UserPrincipal user) {
        var message = messageMapper.findById(messageId,
                messageMapper.findConversationId(messageId).orElseThrow(() -> new com.graduation.ragqa.common.BusinessException(
                        "RESOURCE_NOT_FOUND", "回答不存在", HttpStatus.NOT_FOUND)))
                .orElseThrow(() -> new com.graduation.ragqa.common.BusinessException(
                        "RESOURCE_NOT_FOUND", "回答不存在", HttpStatus.NOT_FOUND));
        conversationService.requireOwnedMessage(messageId, user);
        if (!"ASSISTANT".equals(message.getRole())) {
            throw new com.graduation.ragqa.common.BusinessException("INVALID_MESSAGE", "只能评价系统回答", HttpStatus.BAD_REQUEST);
        }
        feedbackMapper.upsert(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), messageId, user.id(),
                Boolean.TRUE.equals(request.helpful()), Boolean.TRUE.equals(request.citationError()),
                Boolean.TRUE.equals(request.incomplete()), Boolean.TRUE.equals(request.suspectedError()),
                request.comment());
    }

    @GetMapping("/messages/{messageId}/feedback")
    public ApiResponse<FeedbackMapper.FeedbackRow> getFeedback(@PathVariable Long messageId,
                                                                @AuthenticationPrincipal UserPrincipal user) {
        conversationService.requireOwnedMessage(messageId, user);
        return ApiResponse.success(feedbackMapper.find(messageId, user.id()).orElse(null));
    }

    @DeleteMapping("/messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFeedback(@PathVariable Long messageId, @AuthenticationPrincipal UserPrincipal user) {
        conversationService.requireOwnedMessage(messageId, user);
        feedbackMapper.delete(messageId, user.id());
    }
}
