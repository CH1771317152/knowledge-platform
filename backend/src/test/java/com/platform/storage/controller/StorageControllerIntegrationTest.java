package com.platform.storage.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.auth.infrastructure.jwt.JwtTokenProvider;
import com.platform.storage.application.StoragePresignService;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.dto.PresignRequest;
import com.platform.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class StorageControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private StoragePresignService storagePresignService;

    @Test
    void rejectsMissingAccessToken() throws Exception {
        mockMvc.perform(post("/api/storage/presign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"objectKey":"users/42/avatar.png","contentType":"image/png","expiresMinutes":10}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsPresignedUploadForAuthenticatedUser() throws Exception {
        Long userId = 42L;
        PresignedUpload upload = new PresignedUpload("knowledge-platform-test",
                "users/42/avatar.png", "https://oss.example.com/users/42/avatar.png",
                Map.of("Content-Type", "image/png"), LocalDateTime.of(2026, 6, 19, 12, 0));
        when(storagePresignService.presignForUser(eq(userId), org.mockito.ArgumentMatchers.any(PresignRequest.class)))
                .thenReturn(upload);

        mockMvc.perform(post("/api/storage/presign")
                        .header("Authorization", bearer(jwtToken(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"objectKey":"users/42/avatar.png","contentType":"image/png","expiresMinutes":10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectKey").value("users/42/avatar.png"));

        ArgumentCaptor<PresignRequest> requestCaptor = ArgumentCaptor.forClass(PresignRequest.class);
        verify(storagePresignService).presignForUser(eq(userId), requestCaptor.capture());
        PresignRequest request = requestCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(request.objectKey()).isEqualTo("users/42/avatar.png");
        org.assertj.core.api.Assertions.assertThat(request.contentType()).isEqualTo("image/png");
        org.assertj.core.api.Assertions.assertThat(request.expiresMinutes()).isEqualTo(10);
    }

    private String jwtToken(Long userId) {
        return jwtTokenProvider.createAccessToken(userId, "storage-user", UserRole.USER, UUID.randomUUID().toString());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
