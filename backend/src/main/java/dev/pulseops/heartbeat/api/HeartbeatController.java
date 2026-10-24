package dev.pulseops.heartbeat.api;

import dev.pulseops.heartbeat.HeartbeatPayloadTooLargeException;
import dev.pulseops.heartbeat.HeartbeatProperties;
import dev.pulseops.heartbeat.HeartbeatService;
import dev.pulseops.heartbeat.HttpsRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class HeartbeatController {

    static final long MAX_BODY_BYTES = 1024;

    private final HeartbeatService heartbeatService;
    private final HeartbeatProperties properties;

    public HeartbeatController(HeartbeatService heartbeatService, HeartbeatProperties properties) {
        this.heartbeatService = heartbeatService;
        this.properties = properties;
    }

    @PostMapping("/api/v1/heartbeats/{token}")
    public ResponseEntity<Void> report(@PathVariable String token, HttpServletRequest request) throws IOException {
        if (properties.requireHttps() && !isSecure(request)) {
            throw new HttpsRequiredException();
        }
        enforceBodyLimit(request);
        heartbeatService.report(token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/monitors/{id}/heartbeat-token")
    public HeartbeatTokenResponse rotate(@PathVariable Long id) {
        return new HeartbeatTokenResponse(heartbeatService.rotateToken(id));
    }

    @DeleteMapping("/api/v1/monitors/{id}/heartbeat-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id) {
        heartbeatService.revokeToken(id);
    }

    private static boolean isSecure(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }

    private static void enforceBodyLimit(HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            throw new HeartbeatPayloadTooLargeException();
        }
        if (declaredLength >= 0) {
            return;
        }
        long read = 0;
        byte[] buffer = new byte[256];
        int count;
        while ((count = request.getInputStream().read(buffer)) != -1) {
            read += count;
            if (read > MAX_BODY_BYTES) {
                throw new HeartbeatPayloadTooLargeException();
            }
        }
    }
}
