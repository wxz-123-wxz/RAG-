package com.graduation.ragqa.document;

import com.graduation.ragqa.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class DocumentStorageService {

    private final Path storageRoot;

    public DocumentStorageService(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    public String store(Long knowledgeBaseId, Long documentId, MultipartFile file) {
        Path relative = Path.of("kb-" + knowledgeBaseId, "doc-" + documentId, UUID.randomUUID().toString());
        Path target = storageRoot.resolve(relative).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalStateException("Resolved storage path escaped storage root");
        }
        try {
            Files.createDirectories(target.getParent());
            try (var input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return storageRoot.relativize(target).toString().replace('\\', '/');
        } catch (IOException exception) {
            throw new BusinessException("FILE_STORAGE_FAILED", "文件保存失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public Path resolve(String storageKey) {
        Path target = storageRoot.resolve(storageKey).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new BusinessException("INVALID_STORAGE_KEY", "文件记录无效", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return target;
    }

    public void deleteQuietly(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException ignored) {
            // A later cleanup task can remove an orphaned file.
        }
    }
}
