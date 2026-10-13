package com.graduation.ragqa.knowledgebase;

import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.user.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseAccessService {

    private final UserMapper userMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseAccessService(UserMapper userMapper, KnowledgeBaseMapper knowledgeBaseMapper) {
        this.userMapper = userMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    public boolean isSystemAdmin(Long userId) {
        return userMapper.isSystemAdmin(userId) > 0;
    }

    public boolean isKnowledgeBaseAdmin(Long userId) {
        return isSystemAdmin(userId) || userMapper.isKnowledgeBaseAdmin(userId) > 0;
    }

    public KnowledgeBaseRow requireVisible(Long id, Long userId) {
        boolean systemAdmin = isSystemAdmin(userId);
        return knowledgeBaseMapper.findVisibleById(id, userId, systemAdmin)
                .orElseThrow(() -> new BusinessException("RESOURCE_NOT_FOUND", "知识库不存在或无权访问", HttpStatus.NOT_FOUND));
    }

    public void requireManager(Long id, Long userId) {
        requireVisible(id, userId);
        if (!isManager(id, userId)) {
            throw new BusinessException("ACCESS_DENIED", "没有知识库管理权限", HttpStatus.FORBIDDEN);
        }
    }

    public boolean isManager(Long id, Long userId) {
        if (isSystemAdmin(userId)) return true;
        return isCreator(id, userId) || knowledgeBaseMapper.isMember(id, userId) > 0;
    }

    private boolean isCreator(Long id, Long userId) {
        return knowledgeBaseMapper.isCreator(id, userId) > 0;
    }
}
