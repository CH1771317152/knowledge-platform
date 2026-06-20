package com.platform.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Full HTTP-stack integration test for {@link AuthController} and {@link VerificationController}
 * against real MySQL + Redis (the {@code integration} profile). NOT {@code @Transactional} — the
 * controller path is not transactional-rolled-back — so each test method uses a globally-unique
 * email/username/phone suffix (see {@link #unique()}) to avoid collisions with prior runs and with
 * sibling test methods. Created rows are tolerated as harmless residue between runs because of that
 * uniqueness; no explicit cleanup is performed (the shared integration DB accumulates rows, but
 * they never interfere).
 *
 * <p><b>Verification-code observability (design decision #3):</b> the real {@code LoggingVerificationSender}
 * (active under {@code mode=logging}, matchIfMissing) logs the code but the test cannot scrape logs
 * reliably. A {@link TestConfiguration} here provides a {@code @Primary} {@link CapturingSender}
 * that delegates to logging and ALSO records the most recent code per channel+target into a
 * {@link Map} the test can read. The Redis store / hash / Lua scripts still run for real — only the
 * outbound delivery is intercepted — so the verification flow is exercised end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(AuthControllerIntegrationTest.CapturingSenderConfig.class)
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingSenderConfig.CapturingSender capturingSender;

    // --- full register → login → refresh → me → logout flow ----------------------

    @Test
    void registerLoginRefreshMeAndLogoutFlow() throws Exception {
        String suffix = unique();
        String email = "it_auth_" + suffix + "@example.com";
        String username = "it_auth_" + suffix;
        String password = "Sup3rSecret!";

        // 1. Request a REGISTER verification code; capture the dispatched code.
        sendCode(VerificationChannel.EMAIL, email, VerificationPurpose.REGISTER);
        String code = capturingSender.codeFor(VerificationChannel.EMAIL, email);

        // 2. Register with the captured code → token pair + currentUser.
        JsonNode registerBody = register(username, email, password, code);
        String accessToken = registerBody.get("tokenPair").get("accessToken").asText();
        String refreshToken = registerBody.get("tokenPair").get("refreshToken").asText();
        assertThat(registerBody.get("currentUser").get("username").asText()).isEqualTo(username);

        // 3. GET /me with the access token → 200, returns the user.
        MvcResult meResult = mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode meBody = body(meResult);
        assertThat(meBody.get("data").get("username").asText()).isEqualTo(username);
        assertThat(meBody.get("data").get("email").asText()).isEqualTo(email);

        // 4. Refresh → new token pair. Old refresh then fails on reuse.
        JsonNode refreshBody = refresh(refreshToken);
        String newAccessToken = refreshBody.get("accessToken").asText();
        String newRefreshToken = refreshBody.get("refreshToken").asText();
        assertThat(newRefreshToken).isNotEqualTo(refreshToken);

        // Reusing the OLD refresh token must fail (reuse detection burns the family → 4xx).
        mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isGreaterThanOrEqualTo(400);
                });

        // 5. Logout using the NEW refresh token + its access token.
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", bearer(newAccessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(newRefreshToken)))
                .andExpect(status().isOk());

        // 6. GET /me with the access token from step 4 → 401 (jti blacklisted on logout).
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(newAccessToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blacklistedTokenCannotAccessMe() throws Exception {
        String suffix = unique();
        String email = "it_auth_bl_" + suffix + "@example.com";
        String username = "it_auth_bl_" + suffix;
        String password = "Sup3rSecret!";

        sendCode(VerificationChannel.EMAIL, email, VerificationPurpose.REGISTER);
        String code = capturingSender.codeFor(VerificationChannel.EMAIL, email);

        JsonNode registerBody = register(username, email, password, code);
        String accessToken = registerBody.get("tokenPair").get("accessToken").asText();
        String refreshToken = registerBody.get("tokenPair").get("refreshToken").asText();

        // Logout revokes the refresh token AND blacklists the access token's jti.
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken)))
                .andExpect(status().isOk());

        // The blacklisted access token can no longer reach /me → 401.
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isUnauthorized());
    }

    // --- HTTP helpers -------------------------------------------------------------

    private void sendCode(VerificationChannel channel, String target, VerificationPurpose purpose)
            throws Exception {
        mockMvc.perform(post("/api/auth/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendCodeJson(channel, target, purpose)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private JsonNode register(String username, String email, String password, String code)
            throws Exception {
        String json = """
                {"username":"%s","email":"%s","phone":null,"password":"%s",
                 "verificationChannel":"EMAIL","verificationTarget":"%s","verificationCode":"%s"}
                """.formatted(username, email, password, email, code);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenPair.accessToken").exists())
                .andExpect(jsonPath("$.data.tokenPair.refreshToken").exists())
                .andReturn();
        return body(result).get("data");
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andReturn();
        return body(result).get("data");
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

    private static String refreshJson(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    /** Globally-unique suffix per invocation → unique email/username across runs and methods. */
    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // --- test seam ----------------------------------------------------------------

    /**
     * Registers a {@code @Primary} {@link VerificationSender} that records the last code per
     * channel+target while still delegating delivery, so the real verification pipeline (Redis
     * store, hashing, Lua scripts, audit) is exercised — only the outbound send is observable.
     */
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
            public void send(com.platform.auth.domain.VerificationChannel channel,
                             String target, String code) {
                // Key by channel+target so concurrent tests on distinct targets don't clobber each
                // other; normalize email lower-case to match VerificationService normalization.
                String key = key(channel, target);
                codesByTarget.put(key, code);
            }

            String codeFor(com.platform.auth.domain.VerificationChannel channel, String target) {
                String key = key(channel, target);
                String code = codesByTarget.get(key);
                if (code == null) {
                    throw new IllegalStateException("No captured verification code for " + key
                            + "; captured: " + codesByTarget.keySet());
                }
                return code;
            }

            private static String key(com.platform.auth.domain.VerificationChannel channel, String target) {
                String normalized = channel == com.platform.auth.domain.VerificationChannel.EMAIL
                        && target != null ? target.toLowerCase() : target;
                return channel + ":" + normalized;
            }
        }
    }
}
