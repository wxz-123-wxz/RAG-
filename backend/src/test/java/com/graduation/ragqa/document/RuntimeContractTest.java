package com.graduation.ragqa.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.ragqa.config.RagProperties;
import com.graduation.ragqa.knowledgebase.KnowledgeBaseAccessService;
import com.graduation.ragqa.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RuntimeContractTest {
    @Test
    void indexClientUsesPythonFieldNamesAndAuthentication() {
        var builder = RestClient.builder().baseUrl("http://localhost:8000");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:8000/internal/v1/knowledge-bases/7/documents/index"))
                .andExpect(header("Authorization", "Bearer test-only"))
                .andExpect(content().json("""
                  {"document_version_id":"9","document_name":"a.txt","chunks":["alpha"],
                   "chunk_size":600,"chunk_overlap":100,"language":"software-engineering"}
                  """, true)).andRespond(withSuccess());
        new RagIndexClient(builder.build(), new RagProperties("http://localhost:8000", "test-only", Duration.ofSeconds(2)))
                .indexDocument(7L, 9L, "a.txt", new String[]{"alpha"});
        server.verify();
    }

    @Test
    void onlyReadyDocumentsCanBePublished() {
        var access = mock(KnowledgeBaseAccessService.class);
        var mapper = mock(DocumentMapper.class);
        var service = new DocumentService(access, mapper, null, null, null, null, null);
        var user = new UserPrincipal(1L, "admin", "Admin", "unused", "ACTIVE", Set.of("SYS_ADMIN"));
        when(mapper.findCurrentVersion(7L, 8L)).thenReturn(Optional.of(9L));
        when(mapper.findVersionStatus(9L)).thenReturn(Optional.of("FAILED"));
        assertThatThrownBy(() -> service.publish(7L, 8L, user)).hasMessageContaining("完成索引");
        verify(mapper, never()).updateDocumentStatusByVersion(any(), any());
        when(mapper.findVersionStatus(9L)).thenReturn(Optional.of("READY"));
        service.publish(7L, 8L, user);
        verify(mapper).updateDocumentStatusByVersion(9L, "PUBLISHED");
    }
}
