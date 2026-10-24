package dev.pulseops.heartbeat.api;

import dev.pulseops.heartbeat.HeartbeatPayloadTooLargeException;
import dev.pulseops.heartbeat.HeartbeatProperties;
import dev.pulseops.heartbeat.HeartbeatRateLimiter;
import dev.pulseops.heartbeat.HeartbeatService;
import dev.pulseops.heartbeat.HeartbeatTokenGenerator;
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
import java.net.InetAddress;
import java.net.UnknownHostException;

@RestController
public class HeartbeatController {

    static final long MAX_BODY_BYTES = 1024;

    private final HeartbeatService heartbeatService;
    private final HeartbeatTokenGenerator tokenGenerator;
    private final HeartbeatRateLimiter rateLimiter;
    private final HeartbeatProperties properties;

    public HeartbeatController(HeartbeatService heartbeatService, HeartbeatTokenGenerator tokenGenerator,
                               HeartbeatRateLimiter rateLimiter, HeartbeatProperties properties) {
        this.heartbeatService = heartbeatService;
        this.tokenGenerator = tokenGenerator;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping("/api/v1/heartbeats/{token}")
    public ResponseEntity<Void> report(@PathVariable String token, HttpServletRequest request) throws IOException {
        rateLimiter.recordRequest(tokenGenerator.digest(token));
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
        if (request.isSecure()) {
            return true;
        }
        return isLoopback(request.getRemoteAddr())
                && "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }

    private static boolean isLoopback(String remoteAddress) {
        if (remoteAddress == null) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
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
