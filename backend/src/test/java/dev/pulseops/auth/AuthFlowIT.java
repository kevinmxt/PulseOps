package dev.pulseops.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class AuthFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    Environment environment;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    private static final String ADMIN_PASSWORD = "correct-horse-battery";

    @BeforeEach
    void resetAdminPassword() {
        AppUserEntity admin = userRepository.findByUsername("test-admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setUpdatedAt(Instant.now());
        userRepository.save(admin);
    }

    @Test
    void sessionEndpointIsPublicAndBootstrapsCsrfForAnonymousCallers() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.username").value(nullValue()))
                .andExpect(jsonPath("$.role").value(nullValue()))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
    }

    @Test
    void protectedEndpointRejectsAnonymousRequestsWithJsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/monitors"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void writeRequestWithoutCsrfReturnsCsrfInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void healthStaysPublicWhileMetricsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void sessionCookieAndLifetimeSettingsMatchTheSpec() {
        assertThat(environment.getProperty("server.servlet.session.cookie.name"))
                .isEqualTo("PULSEOPS_SESSION");
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only"))
                .isEqualTo("true");
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site"))
                .isEqualTo("lax");
        assertThat(environment.getProperty("server.servlet.session.cookie.path"))
                .isEqualTo("/");
        assertThat(environment.getProperty("server.servlet.session.timeout"))
                .isEqualTo("30m");
    }

    @Test
    void forwardHeaderTrustIsLimitedToTheEdgeSubnet() {
        assertThat(environment.getProperty("server.forward-headers-strategy"))
                .isEqualTo("native");
        String internalProxies = environment.getProperty(
                "server.tomcat.remoteip.internal-proxies");
        assertThat(internalProxies).isEqualTo(ProxyRateLimitSupport.EDGE_PROXY_REGEX);
        assertThat(Pattern.matches(internalProxies, "172.28.0.3")).isTrue();
        assertThat(Pattern.matches(internalProxies, "172.29.0.50")).isFalse();
        assertThat(Pattern.matches(internalProxies, "127.0.0.1")).isFalse();
    }

    @Test
    void authLogsDoNotContainCredentialsSessionIdsOrCsrfTokens(CapturedOutput output) throws Exception {
        String username = "logcheck-" + UUID.randomUUID();
        String password = "log-check-password-123";
        String newPassword = "log-check-password-456";
        createUser(username, password);
        Csrf csrf = bootstrapCsrf();

        loginExpectingInvalidCredentials(username, "wrong-password-999", csrf);
        MvcResult loginResult = mockMvc.perform(login(username, password, csrf, null))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        passwordChange(session, csrf, password, newPassword)
                .andExpect(status().isNoContent());

        assertThat(output.getAll())
                .doesNotContain(username)
                .doesNotContain(password)
                .doesNotContain(newPassword)
                .doesNotContain(csrf.token());
    }

    @Test
    void passwordChangeValidatesCurrentAndNewPassword() throws Exception {
        Csrf csrf = bootstrapCsrf();
        MockHttpSession session = loginSession(csrf);

        passwordChange(session, csrf, "wrong-password-123", "new-password-123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));

        passwordChange(session, csrf, ADMIN_PASSWORD, "too-short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        passwordChange(session, csrf, ADMIN_PASSWORD, ADMIN_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/monitors").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void passwordChangeInvalidatesAllSessionsAndSwitchesCredentials() throws Exception {
        Csrf csrf = bootstrapCsrf();
        MockHttpSession firstSession = loginSession(csrf);
        MockHttpSession secondSession = loginSession(csrf);
        String newPassword = "brand-new-password-123";

        passwordChange(firstSession, csrf, ADMIN_PASSWORD, newPassword)
                .andExpect(status().isNoContent());

        assertThat(firstSession.isInvalid()).isTrue();
        assertThat(secondSession.isInvalid()).isTrue();

        mockMvc.perform(get("/api/v1/monitors"))
                .andExpect(status().isUnauthorized());

        loginExpectingInvalidCredentials("test-admin", ADMIN_PASSWORD, csrf);
        mockMvc.perform(login("test-admin", newPassword, csrf, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-admin"));
    }

    @Test
    void sessionsPastTheAbsoluteLifetimeRequireReauthentication() throws Exception {
        Csrf csrf = bootstrapCsrf();
        MockHttpSession session = loginSession(csrf);
        session.setAttribute(AbsoluteSessionTimeoutFilter.SESSION_CREATED_AT_ATTRIBUTE,
                Instant.now().minus(AbsoluteSessionTimeoutFilter.MAX_SESSION_LIFETIME).minusSeconds(1));

        mockMvc.perform(get("/api/v1/monitors").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void heartbeatReportsStayPublicAndCsrfExempt() throws Exception {
        Csrf csrf = bootstrapCsrf();
        MockHttpSession session = loginSession(csrf);

        String response = mockMvc.perform(post("/api/v1/monitors")
                        .session(session)
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"public-heartbeat","type":"HEARTBEAT"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String heartbeatToken = objectMapper.readTree(response).get("heartbeatToken").asText();

        mockMvc.perform(post("/api/v1/heartbeats/{token}", heartbeatToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void actuatorMetricsRequiresAnAdminSession() throws Exception {
        Csrf csrf = bootstrapCsrf();
        MockHttpSession session = loginSession(csrf);

        mockMvc.perform(get("/actuator/metrics").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void loginReturnsUsernameAndRoleAndRotatesSessionId() throws Exception {
        Csrf csrf = bootstrapCsrf();
        MockHttpSession preLoginSession = new MockHttpSession();

        MvcResult result = mockMvc.perform(login("test-admin", ADMIN_PASSWORD, csrf, preLoginSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull().isNotSameAs(preLoginSession);
        assertThat(session.getId()).isNotEqualTo(preLoginSession.getId());
        assertThat(preLoginSession.isInvalid()).isTrue();
        assertThat(session.getAttribute(AbsoluteSessionTimeoutFilter.SESSION_CREATED_AT_ATTRIBUTE))
                .isInstanceOf(Instant.class);

        mockMvc.perform(get("/api/v1/monitors").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("test-admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void wrongCredentialsReturnTheSameInvalidCredentialsEnvelope() throws Exception {
        Csrf csrf = bootstrapCsrf();

        JsonNode existingUser = loginError("test-admin", "wrong-password-123", csrf);
        JsonNode unknownUser = loginError("no-such-user", "wrong-password-123", csrf);

        assertThat(existingUser.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(existingUser.get("message").asText())
                .isEqualTo(unknownUser.get("message").asText());
        assertThat(existingUser.get("traceId").asText()).isNotEmpty();
        assertThat(unknownUser.get("traceId").asText()).isNotEmpty();
    }

    @Test
    void loginRequiresCsrfAndRejectsMismatchedTokens() throws Exception {
        Csrf csrf = bootstrapCsrf();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("test-admin", ADMIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("test-admin", ADMIN_PASSWORD))
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", "not-the-real-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void logoutInvalidatesTheSessionAndIsIdempotent() throws Exception {
        Csrf csrf = bootstrapCsrf();
        MockHttpSession session = loginSession(csrf);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .session(session)
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();

        mockMvc.perform(get("/api/v1/monitors"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void successfulLoginClearsPreviousFailuresForTheSameKey() throws Exception {
        String username = "clear-" + UUID.randomUUID();
        String password = "known-password-123";
        createUser(username, password);
        Csrf csrf = bootstrapCsrf();

        for (int attempt = 0; attempt < 4; attempt++) {
            loginExpectingInvalidCredentials(username, "wrong-password-123", csrf);
        }

        mockMvc.perform(login(username, password, csrf, null))
                .andExpect(status().isOk());

        for (int attempt = 0; attempt < 5; attempt++) {
            loginExpectingInvalidCredentials(username, "wrong-password-123", csrf);
        }
    }

    @Test
    void rateLimitsAfterFiveFailuresAndReturnsRetryAfter() throws Exception {
        String username = "limited-" + UUID.randomUUID();
        Csrf csrf = bootstrapCsrf();

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS; attempt++) {
            loginExpectingInvalidCredentials(username, "wrong-password-123", csrf);
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, "wrong-password-123"))
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private Csrf bootstrapCsrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("csrfToken").asText();
        assertThat(cookie.getValue()).isEqualTo(token);
        return new Csrf(cookie, token);
    }

    private MockHttpServletRequestBuilder login(String username, String password, Csrf csrf,
                                                MockHttpSession session) {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(username, password))
                .cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token());
        if (session != null) {
            request.session(session);
        }
        return request;
    }

    private org.springframework.test.web.servlet.ResultActions passwordChange(
            MockHttpSession session, Csrf csrf, String currentPassword, String newPassword)
            throws Exception {
        return mockMvc.perform(put("/api/v1/auth/password")
                .session(session)
                .cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"%s","newPassword":"%s"}
                        """.formatted(currentPassword, newPassword)));
    }

    private MockHttpSession loginSession(Csrf csrf) throws Exception {
        MvcResult result = mockMvc.perform(login("test-admin", ADMIN_PASSWORD, csrf, null))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void loginExpectingInvalidCredentials(String username, String password, Csrf csrf)
            throws Exception {
        mockMvc.perform(login(username, password, csrf, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private JsonNode loginError(String username, String password, Csrf csrf) throws Exception {
        return objectMapper.readTree(mockMvc.perform(login(username, password, csrf, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString());
    }

    private void createUser(String username, String password) {
        AppUserEntity user = new AppUserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(AppRole.ADMIN);
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
    }

    private static String loginJson(String username, String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private record Csrf(Cookie cookie, String token) {
    }
}
