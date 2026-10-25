package dev.pulseops.auth;

import dev.pulseops.common.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    public static final String SESSION_CREATED_AT_ATTRIBUTE = "pulseops.auth.session-created-at";
    public static final Duration MAX_SESSION_LIFETIME = Duration.ofHours(12);

    private static final Logger log = LoggerFactory.getLogger(AbsoluteSessionTimeoutFilter.class);

    private final Clock clock;

    public AbsoluteSessionTimeoutFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            HttpSession session = request.getSession(false);
            Object createdAt = session == null ? null : session.getAttribute(SESSION_CREATED_AT_ATTRIBUTE);
            if (createdAt instanceof Instant created
                    && !clock.instant().isBefore(created.plus(MAX_SESSION_LIFETIME))) {
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // Already expired by the container.
                }
                SecurityContextHolder.clearContext();
                log.info("Admin session exceeded the absolute lifetime, remoteAddress={}, traceId={}",
                        request.getRemoteAddr(), TraceIdFilter.currentTraceId(request));
            }
        }
        filterChain.doFilter(request, response);
    }
}
