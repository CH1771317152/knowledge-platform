package com.platform.counter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.infrastructure.verification.VerificationSender;
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
 * Full HTTP-stack integration test for {@link CounterController} against real MySQL + Redis (the
 * {@code integration} profile). Mirrors the {@code ContentControllerIntegrationTest} pattern: a
 * {@code @Primary CapturingSender} captures register verification codes so two real users are
 * registered (the author and the liker), a draft article is created by the author (a draft is
 * sufficient — {@code ContentQueryService#findPostAuthorId} only filters out soft-deleted posts, not
 * drafts, so the counter's existence/author lookup succeeds against a draft), then the interaction
 * endpoints are driven over HTTP with Bearer tokens.
 *
 * <p><b>What is and is NOT asserted (critical):</b> the aggregated {@code CountInt} blob is updated
 * <i>asynchronously</i> by {@code CounterAggregateConsumer} + {@code CounterFlushScheduler}, both of
 * which are gated with {@code @Profile("!test & !integration")} and therefore <b>do not run</b> in
 * this test. The aggregated counter values (like/view/etc. totals) are NOT flushed in time and are
 * NOT asserted here. Instead this test asserts the <b>bitmap</b> state — the synchronous,
 * Redis-strongly-consistent source of truth for "has this user acted on this entity" — via
 * {@code GET /counters/liked} (and implicitly via the {@code changed} flag on the like/unlike
 * responses, which is driven by the bitmap transition).
 *
 * <p>Not {@code @Transactional}; each run uses a unique username/email (see {@link #unique()}) to
 * avoid collisions in the shared integration DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(CounterControllerIntegrationTest.CapturingSenderConfig.class)
class CounterControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingSenderConfig.CapturingSender capturingSender;

    @Test
    void likeUnlikeViewAndPublicCounters() throws Exception {
        String suffix = unique();
        String authorEmail = "it_counter_a_" + suffix + "@example.com";
        String authorUsername = "it_counter_a_" + suffix;
        String likerEmail = "it_counter_b_" + suffix + "@example.com";
        String likerUsername = "it_counter_b_" + suffix;
        String password = "Sup3rSecret!";

        String authorToken = registerUser(authorUsername, authorEmail, password);
        String likerToken = registerUser(likerUsername, likerEmail, password);

        long postId = createDraft(authorToken);
        assertThat(postId).isGreaterThan(0L);

        // Before liking, the liker has not acted.
        mockMvc.perform(get("/api/posts/" + postId + "/counters/liked")
                        .header("Authorization", bearer(likerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        // First like -> real bitmap transition -> changed=true.
        mockMvc.perform(post("/api/posts/" + postId + "/likes")
                        .header("Authorization", bearer(likerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));

        // Second like by the same user -> bitmap already set -> idempotent no-op -> changed=false.
        mockMvc.perform(post("/api/posts/" + postId + "/likes")
                        .header("Authorization", bearer(likerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(false));

        // Bitmap state now reflects the like.
        mockMvc.perform(get("/api/posts/" + postId + "/counters/liked")
                        .header("Authorization", bearer(likerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // Anonymous (no token) read of the public counters -> 200 (permitAll matcher works).
        mockMvc.perform(get("/api/posts/" + postId + "/counters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").value(postId));

        // Unlike -> bitmap 1->0 -> changed=true.
        mockMvc.perform(delete("/api/posts/" + postId + "/likes")
                        .header("Authorization", bearer(likerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));

        // After unlike the bitmap is clear.
        mockMvc.perform(get("/api/posts/" + postId + "/counters/liked")
                        .header("Authorization", bearer(likerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        // view WITHOUT Idempotency-Key -> 400 (COUNTER_EVENT_INVALID; blank key rejected).
        mockMvc.perform(post("/api/posts/" + postId + "/views")
                        .header("Authorization", bearer(likerToken)))
                .andExpect(status().isBadRequest());

        // view WITH Idempotency-Key -> 200.
        mockMvc.perform(post("/api/posts/" + postId + "/views")
                        .header("Authorization", bearer(likerToken))
                        .header("Idempotency-Key", "view-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));

        // Favorite works the same way as like (bitmap-gated).
        mockMvc.perform(post("/api/posts/" + postId + "/favorites")
                        .header("Authorization", bearer(likerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));
    }

    @Test
    void writesRejectMissingToken() throws Exception {
        // A like write without an access token -> 401 (anyRequest().authenticated()).
        mockMvc.perform(post("/api/posts/1/likes"))
                .andExpect(status().isUnauthorized());

        // The authenticated counters/liked endpoint without a token -> 401.
        mockMvc.perform(get("/api/posts/1/counters/liked"))
                .andExpect(status().isUnauthorized());

        // The public counters endpoint with no token -> 200 (permitAll).
        mockMvc.perform(get("/api/posts/1/counters"))
                .andExpect(status().isOk());
    }

    @Test
    void likeOnMissingPostIsNotFound() throws Exception {
        String suffix = unique();
        String token = registerUser("it_counter_c_" + suffix,
                "it_counter_c_" + suffix + "@example.com", "Sup3rSecret!");
        // A post id that does not exist -> COUNTER_ENTITY_NOT_FOUND -> 400 (default mapping).
        mockMvc.perform(post("/api/posts/999999999/likes")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
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

    // --- test seam (verification-code capture, copied from ContentControllerIntegrationTest) -------

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
