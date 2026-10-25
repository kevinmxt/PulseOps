package dev.pulseops.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * MockMvc request builders that keep the real security chain active by supplying an
 * authenticated admin and, for writes, a valid CSRF token.
 */
public final class AuthenticatedApiRequests {

    public static final String ADMIN_USERNAME = "test-admin";

    private AuthenticatedApiRequests() {
    }

    public static MockHttpServletRequestBuilder get(String urlTemplate, Object... uriVariables) {
        return MockMvcRequestBuilders.get(urlTemplate, uriVariables).with(admin());
    }

    public static MockHttpServletRequestBuilder post(String urlTemplate, Object... uriVariables) {
        return MockMvcRequestBuilders.post(urlTemplate, uriVariables).with(admin()).with(csrf());
    }

    public static MockHttpServletRequestBuilder put(String urlTemplate, Object... uriVariables) {
        return MockMvcRequestBuilders.put(urlTemplate, uriVariables).with(admin()).with(csrf());
    }

    public static MockHttpServletRequestBuilder delete(String urlTemplate, Object... uriVariables) {
        return MockMvcRequestBuilders.delete(urlTemplate, uriVariables).with(admin()).with(csrf());
    }

    public static MockHttpServletRequestBuilder patch(String urlTemplate, Object... uriVariables) {
        return MockMvcRequestBuilders.patch(urlTemplate, uriVariables).with(admin()).with(csrf());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }
}
