package dev.pulseops.heartbeat;

import dev.pulseops.heartbeat.api.HeartbeatController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class HeartbeatControllerTest {

    private final HeartbeatService service = mock(HeartbeatService.class);
    private final HeartbeatController controller = new HeartbeatController(
            service, new HeartbeatTokenGenerator(), new HeartbeatRateLimiter(Clock.systemUTC()),
            new HeartbeatProperties(false, Duration.ofSeconds(5), 100, true));

    @Test
    void rejectsChunkedBodiesLargerThanOneKilobyte() {
        MockHttpServletRequest request = chunkedRequest("a".repeat(1025));

        assertThatThrownBy(() -> controller.report("token", request))
                .isInstanceOf(HeartbeatPayloadTooLargeException.class);
        verifyNoInteractions(service);
    }

    @Test
    void acceptsChunkedBodiesAtTheLimit() throws Exception {
        MockHttpServletRequest request = chunkedRequest("a".repeat(1024));

        assertThatCode(() -> controller.report("token", request)).doesNotThrowAnyException();
        verify(service).report("token");
    }

    private static MockHttpServletRequest chunkedRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
