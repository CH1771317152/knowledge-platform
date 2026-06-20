package com.platform.relation.controller;

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
import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.RelationEventType;
import com.platform.relation.event.RelationEventPayload;
import com.platform.relation.repository.RelationRepository;
import java.time.LocalDateTime;
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
 * Full HTTP-stack integration test for {@link RelationController} against real MySQL + Redis (the
 * {@code integration} profile). Mirrors {@code ContentControllerIntegrationTest}: a
 * {@code @Primary CapturingSender} captures the register verification code, two real users (A and B)
 * are registered to obtain A's access token, then the follow/unfollow/relation/list endpoints are
 * driven over HTTP.
 *
 * <p>The async follower projector is gated out of the integration profile, so the followers-list case
 * seeds the {@code relation_follower} projection directly via the injected {@link RelationRepository}
 * (the same {@code upsertFollowerProjection} call the projector makes in production). Not
 * {@code @Transactional}; each run uses unique usernames/emails (see {@link #unique()}) to avoid
 * collisions in the shared integration DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(RelationControllerIntegrationTest.CapturingSenderConfig.class)
class RelationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingSenderConfig.CapturingSender capturingSender;

    @Autowired
    private RelationRepository relationRepository;

    @Test
    void followUnfollowRelationAndLists() throws Exception {
        String suffix = unique();
        String emailA = "it_rel_a_" + suffix + "@example.com";
        String usernameA = "it_rel_a_" + suffix;
        String emailB = "it_rel_b_" + suffix + "@example.com";
        String usernameB = "it_rel_b_" + suffix;
        String password = "Sup3rSecret!";

        RegisteredUser a = registerUser(usernameA, emailA, password);
        RegisteredUser b = registerUser(usernameB, emailB, password);

        // 1. A follows B → 200, following=true.
        mockMvc.perform(post("/api/users/" + b.userId() + "/follow")
                        .header("Authorization", bearer(a.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(true))
                .andExpect(jsonPath("$.data.currentUserId").value(a.userId()))
                .andExpect(jsonPath("$.data.targetUserId").value(b.userId()));

        // 2. A checks relation to B → following=true.
        mockMvc.perform(get("/api/users/" + b.userId() + "/relation")
                        .header("Authorization", bearer(a.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(true));

        // 3. Anonymous GET following list (public read) → 200.
        mockMvc.perform(get("/api/users/" + a.userId() + "/following"))
                .andExpect(status().isOk());

        // 4. A unfollows B → 200, following=false.
        mockMvc.perform(delete("/api/users/" + b.userId() + "/follow")
                        .header("Authorization", bearer(a.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(false))
                .andExpect(jsonPath("$.data.followedAt").doesNotExist());

        // 5. A checks relation to B → following=false.
        mockMvc.perform(get("/api/users/" + b.userId() + "/relation")
                        .header("Authorization", bearer(a.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(false));

        // 6. POST follow with NO token → 401 (protected).
        mockMvc.perform(post("/api/users/" + b.userId() + "/follow")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // 7. Followers list via direct projection: the async projector is gated out of integration,
        //    so seed B's follower projection (A is B's fan) the same way the projector would.
        //    This re-follows B first (the earlier unfollow left the projection absent), then upserts
        //    the follower row directly.
        mockMvc.perform(post("/api/users/" + b.userId() + "/follow")
                        .header("Authorization", bearer(a.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(true));

        LocalDateTime occurredAt = LocalDateTime.now();
        RelationEventPayload payload = new RelationEventPayload(
                UUID.randomUUID().toString(), RelationEventType.USER_FOLLOWED, "FOLLOW",
                "FOLLOW:" + a.userId() + ":" + b.userId(), a.userId(), b.userId(), occurredAt);
        relationRepository.upsertFollowerProjection(payload, FollowStatus.ACTIVE);

        // 8. Anonymous GET followers list (public read) → 200, includes A.
        mockMvc.perform(get("/api/users/" + b.userId() + "/followers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId == " + a.userId() + ")]").exists());
    }

    @Test
    void protectedEndpointsRejectMissingToken() throws Exception {
        // GET /api/users/{userId}/relation requires auth → 401 without a token (NOT permitAll'd —
        // it reveals the current user's follow state).
        mockMvc.perform(get("/api/users/1/relation"))
                .andExpect(status().isUnauthorized());

        // DELETE follow requires auth → 401 without a token.
        mockMvc.perform(delete("/api/users/1/follow"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCanListFollowingAndFollowersWithoutToken() throws Exception {
        // The following/followers lists are permitAll at the filter layer — no token reaches the
        // controller. (User id 1 may or may not exist; the public list simply returns [] for a
        // missing user, still 200.)
        mockMvc.perform(get("/api/users/1/following").param("page", "0").param("size", "5"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/1/followers").param("page", "0").param("size", "5"))
                .andExpect(status().isOk());
    }

    // --- HTTP helpers ----------------------------------------------------------

    private RegisteredUser registerUser(String username, String email, String password) throws Exception {
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
        JsonNode data = body(result).get("data");
        String token = data.get("tokenPair").get("accessToken").asText();
        long userId = data.get("currentUser").get("userId").asLong();
        assertThat(userId).isGreaterThan(0L);
        return new RegisteredUser(userId, token);
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

    private record RegisteredUser(long userId, String token) {}

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
