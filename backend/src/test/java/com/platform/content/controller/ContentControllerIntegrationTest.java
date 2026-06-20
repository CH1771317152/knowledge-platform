package com.platform.content.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.infrastructure.verification.VerificationSender;
import com.platform.storage.infrastructure.FakeObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full HTTP-stack integration test for {@link ContentController} against real MySQL + Redis (the
 * {@code integration} profile) and the active {@link FakeObjectStorageService} bean. Mirrors the
 * {@code AuthControllerIntegrationTest} pattern: a {@code @Primary CapturingSender} captures the
 * register verification code, a real user is registered to obtain an access token, then the full
 * six-stage content workflow is driven over HTTP. Not {@code @Transactional}; each run uses a unique
 * username/email (see {@link #unique()}) to avoid collisions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(ContentControllerIntegrationTest.CapturingSenderConfig.class)
class ContentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingSenderConfig.CapturingSender capturingSender;

    @Autowired
    private FakeObjectStorageService objectStorage;

    @Test
    void fullWorkflowAnonymousReadAndUnpublish() throws Exception {
        String suffix = unique();
        String email = "it_content_" + suffix + "@example.com";
        String username = "it_content_" + suffix;
        String password = "Sup3rSecret!";
        String token = registerUser(username, email, password);

        // 1. Create a draft.
        long postId = createDraft(token);
        assertThat(postId).isGreaterThan(0L);

        // 2. Request the body upload URL.
        JsonNode upload = body(postJson("/api/posts/" + postId + "/body/upload-url", token, "{}"));
        String objectKey = upload.get("data").get("objectKey").asText();
        assertThat(objectKey).isEqualTo("posts/" + postId + "/body/v1.md");

        // 3. Put the Markdown bytes into the fake object storage at the returned object key.
        byte[] content = "# Integration body\n\nHello world.".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);
        objectStorage.putObject(objectKey, "text/markdown", content, "etag-it");

        // 4. Confirm the body with the real size/etag/sha.
        String confirmJson = """
                {"objectKey":"%s","etag":"etag-it","sizeBytes":%d,"sha256":"%s"}
                """.formatted(objectKey, content.length, sha);
        mockMvc.perform(post("/api/posts/" + postId + "/body/confirm")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishStage").value("BODY_CONFIRMED"));

        // 5. Update metadata (visibility PUBLIC so it is world-readable after publish).
        String metadataJson = """
                {"title":"My Integration Post","summary":"s","visibility":"PUBLIC",
                 "coverObjectKey":null,"files":[],"tags":["go","java"]}
                """.formatted();
        mockMvc.perform(put("/api/posts/" + postId + "/metadata")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metadataJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadataCompleted").value(true));

        // 6. Publish.
        mockMvc.perform(post("/api/posts/" + postId + "/publish")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // 7. Anonymous GET detail → 200, body markdown present.
        MvcResult detailResult = mockMvc.perform(get("/api/posts/" + postId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = body(detailResult).get("data");
        assertThat(detail.get("body").asText()).contains("# Integration body");
        assertThat(detail.get("summary").get("visibility").asText()).isEqualTo("PUBLIC");
        // Tags is a flat list of names in PostSummaryResponse.
        assertThat(detail.get("summary").get("tags").isArray()).isTrue();
        assertThat(detail.get("summary").get("tags").size()).isGreaterThan(0);

        // 8. Anonymous GET list → includes the post.
        mockMvc.perform(get("/api/posts").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.postId == " + postId + ")]").exists());

        // 9. GET /me with the author's token → includes the post too.
        mockMvc.perform(get("/api/posts/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.postId == " + postId + ")]").exists());

        // 10. Author unpublish.
        mockMvc.perform(post("/api/posts/" + postId + "/unpublish")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // 11. Anonymous GET detail → now draft (not anonymous-readable) → error envelope
        //     (CONTENT_POST_NOT_FOUND → 404 per GlobalExceptionHandler: *_NOT_FOUND codes map to 404).
        mockMvc.perform(get("/api/posts/" + postId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        // 12. Cleanup: soft-delete the post so the shared integration DB does not accumulate a
        // published+public row that would pollute the repository integration test's exact-id assertions.
        mockMvc.perform(delete("/api/posts/" + postId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        // A content write endpoint without an access token → 401.
        mockMvc.perform(post("/api/posts/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        // The author-scoped publishing-state endpoint also requires auth → 401 without a token.
        mockMvc.perform(get("/api/posts/1/publishing-state"))
                .andExpect(status().isUnauthorized());

        // GET /api/posts/me requires auth too → 401 without a token.
        mockMvc.perform(get("/api/posts/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCanListPublicFeedWithoutToken() throws Exception {
        // The public feed is permitAll at the filter layer — no token reaches the controller.
        mockMvc.perform(get("/api/posts").param("page", "0").param("size", "5"))
                .andExpect(status().isOk());
    }

    // --- HTTP helpers ----------------------------------------------------------

    private String registerUser(String username, String email, String password) throws Exception {
        sendCode(VerificationChannel.EMAIL, email, VerificationPurpose.REGISTER);
        String code = capturingSender.codeFor(VerificationChannel.EMAIL, email);
        String json = """
                {"username":"%s","email":"%s","phone":null,"password":"%s",
                 "verificationChannel":"EMAIL","verificationTarget":"%s","verificationCode":"%s"}
                """.formatted(username, email, password, email, code);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data").get("tokenPair").get("accessToken").asText();
    }

    private long createDraft(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/posts/drafts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").exists())
                .andReturn();
        return body(result).get("data").get("postId").asLong();
    }

    private MvcResult postJson(String path, String token, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void sendCode(VerificationChannel channel, String target, VerificationPurpose purpose)
            throws Exception {
        mockMvc.perform(post("/api/auth/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendCodeJson(channel, target, purpose)))
                .andExpect(status().isOk());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String sendCodeJson(VerificationChannel channel, String target,
                                       VerificationPurpose purpose) {
        return "{\"channel\":\"" + channel.name() + "\",\"target\":\"" + target
                + "\",\"purpose\":\"" + purpose.name() + "\"}";
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String sha256Hex(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // --- test seam (verification-code capture, copied from AuthControllerIntegrationTest) -------

    @TestConfiguration
    static class CapturingSenderConfig {

        @Bean
        @Primary
        CapturingSender capturingVerificationSender() {
            return new CapturingSender();
        }

        static class CapturingSender implements VerificationSender {
            private final Map<String, String> codesByTarget = new ConcurrentHashMap<>();

            @Override
            public void send(VerificationChannel channel, String target, String code) {
                codesByTarget.put(key(channel, target), code);
            }

            String codeFor(VerificationChannel channel, String target) {
                String code = codesByTarget.get(key(channel, target));
                if (code == null) {
                    throw new IllegalStateException("No captured verification code for " + target);
                }
                return code;
            }

            private static String key(VerificationChannel channel, String target) {
                String normalized = channel == VerificationChannel.EMAIL && target != null
                        ? target.toLowerCase() : target;
                return channel + ":" + normalized;
            }
        }
    }
}
