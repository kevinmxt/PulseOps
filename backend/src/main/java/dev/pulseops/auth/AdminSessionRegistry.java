package dev.pulseops.auth;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class AdminSessionRegistry implements HttpSessionListener {

    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    public void register(String username, HttpSession session) {
        sessions.put(session.getId(), new SessionEntry(username, session));
    }

    public void unregister(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public void invalidateAll(String username) {
        sessions.entrySet().removeIf(entry -> {
            if (!entry.getValue().username().equals(username)) {
                return false;
            }
            try {
                entry.getValue().session().invalidate();
            } catch (IllegalStateException ignored) {
                // Already expired by the container.
            }
            return true;
        });
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        unregister(event.getSession().getId());
    }

    private record SessionEntry(String username, HttpSession session) {
    }
}
