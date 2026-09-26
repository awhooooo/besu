package co.rsk.federate.btcreleaseclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.Test;

/**
 * Between sending a signature and the bridge counting it, the peg-out still looks like it is
 * waiting. Without this the next turn signs it again and pays for a transaction that changes
 * nothing; with it set too long, a peg-out that genuinely needs signing again is skipped.
 */
class PegoutSignedCacheImplTest {

    private static final Hash A_PEGOUT = Hash.fromHexStringLenient("0xaa");
    private static final Hash ANOTHER = Hash.fromHexStringLenient("0xbb");

    /** A clock the test moves by hand, so nothing here waits on real time. */
    private static final class TickingClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void whatWasJustSignedIsRemembered() {
        PegoutSignedCache cache = new PegoutSignedCacheImpl(Duration.ofMinutes(30), new TickingClock());

        assertThat(cache.hasAlreadyBeenSigned(A_PEGOUT)).isFalse();
        cache.put(A_PEGOUT);
        assertThat(cache.hasAlreadyBeenSigned(A_PEGOUT)).isTrue();
    }

    @Test
    void oneEntryDoesNotHideAnother() {
        PegoutSignedCache cache = new PegoutSignedCacheImpl(Duration.ofMinutes(30), new TickingClock());

        cache.put(A_PEGOUT);

        assertThat(cache.hasAlreadyBeenSigned(ANOTHER)).isFalse();
    }

    @Test
    void theMemoryLapsesSoAPegoutCanBeSignedAgain() {
        TickingClock clock = new TickingClock();
        PegoutSignedCache cache = new PegoutSignedCacheImpl(Duration.ofMinutes(30), clock);
        cache.put(A_PEGOUT);

        clock.advance(Duration.ofMinutes(29));
        assertThat(cache.hasAlreadyBeenSigned(A_PEGOUT)).isTrue();

        clock.advance(Duration.ofMinutes(2));
        assertThat(cache.hasAlreadyBeenSigned(A_PEGOUT)).isFalse();
    }

    @Test
    void signingAgainRestartsTheClockOnThatPegout() {
        TickingClock clock = new TickingClock();
        PegoutSignedCache cache = new PegoutSignedCacheImpl(Duration.ofMinutes(30), clock);

        cache.put(A_PEGOUT);
        clock.advance(Duration.ofMinutes(20));
        cache.put(A_PEGOUT);
        clock.advance(Duration.ofMinutes(20));

        assertThat(cache.hasAlreadyBeenSigned(A_PEGOUT)).isTrue();
    }

    @Test
    void whatHasNeverBeenSignedIsNotRemembered() {
        PegoutSignedCache cache = new PegoutSignedCacheImpl(Duration.ofMinutes(30), new TickingClock());

        assertThat(cache.hasAlreadyBeenSigned(A_PEGOUT)).isFalse();
        assertThat(cache.hasAlreadyBeenSigned(null)).isFalse();
    }

    @Test
    void aCacheThatForgetsNothingOrEverythingIsRefused() {
        assertThatThrownBy(() -> new PegoutSignedCacheImpl(Duration.ZERO, new TickingClock()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PegoutSignedCacheImpl(Duration.ofMinutes(-1), new TickingClock()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
