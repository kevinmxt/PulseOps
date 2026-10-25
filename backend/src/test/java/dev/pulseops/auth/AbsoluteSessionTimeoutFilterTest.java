package dev.pulseops.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbsoluteSessionTimeoutFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

    private final AbsoluteSessionTimeoutFilter filter =
            new AbsoluteSessionTimeoutFilter(Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidatesAuthenticatedSessionsPastTheAbsoluteLifetime() throws Exception {
        MockHttpSession session = authenticatedSession(NOW
                .minus(AbsoluteSessionTimeoutFilter.MAX_SESSION_LIFETIME)
                .minusSeconds(1));
        MockHttpServletRequest request = requestWith(session);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void keepsSessionsWithinTheAbsoluteLifetime() throws Exception {
        MockHttpSession session = authenticatedSession(NOW
                .minus(AbsoluteSessionTimeoutFilter.MAX_SESSION_LIFETIME)
                .plusSeconds(1));
        MockHttpServletRequest request = requestWith(session);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void ignoresSessionsWithoutACreationTimestamp() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWith(session);
        authenticate();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
    }

    private static MockHttpSession authenticatedSession(Instant createdAt) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AbsoluteSessionTimeoutFilter.SESSION_CREATED_AT_ATTRIBUTE, createdAt);
        authenticate();
        return session;
    }

    private static void authenticate() {
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
    }

    private static MockHttpServletRequest requestWith(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }
}
