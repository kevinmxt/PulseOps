package dev.pulseops.heartbeat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatTokenGeneratorTest {

    private final HeartbeatTokenGenerator generator = new HeartbeatTokenGenerator();

    @Test
    void generatesUrlSafeTokenWith256BitsOfEntropy() {
        String token = generator.generate();

        assertThat(token).hasSize(43);
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generatesDistinctTokens() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(generator.generate());
        }

        assertThat(tokens).hasSize(100);
    }

    @Test
    void digestsTokenAsLowercaseSha256Hex() {
        assertThat(generator.digest("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
