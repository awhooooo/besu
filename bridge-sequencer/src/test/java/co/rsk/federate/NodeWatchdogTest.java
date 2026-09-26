package co.rsk.federate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import co.rsk.federate.rpc.RpcException;
import org.junit.jupiter.api.Test;

/**
 * A node restarting is ordinary and worth waiting out; a node that is gone is not. Getting the line
 * between them wrong is expensive either way: too eager and a restart kills the sequencer, too
 * patient and it sits there looking alive while informing the bridge of nothing.
 */
class NodeWatchdogTest {

    /** Counts what it was asked to wait, instead of waiting. */
    private static final class RecordingPause implements NodeWatchdog.Pause {
        private final List<Long> waits = new ArrayList<>();

        @Override
        public void forMillis(long millis) {
            waits.add(millis);
        }
    }

    @Test
    void aNodeThatAnswersAtOnceIsNotWaitedFor() {
        RecordingPause pause = new RecordingPause();
        NodeWatchdog watchdog = new NodeWatchdog(10, 30_000, pause);

        assertThat(watchdog.awaitReachable(() -> true)).isTrue();
        assertThat(pause.waits).isEmpty();
    }

    @Test
    void aNodeStartingUpIsWaitedFor() {
        RecordingPause pause = new RecordingPause();
        NodeWatchdog watchdog = new NodeWatchdog(10, 30_000, pause);
        AtomicInteger attempts = new AtomicInteger();

        assertThat(watchdog.awaitReachable(() -> attempts.incrementAndGet() >= 4)).isTrue();
        assertThat(attempts).hasValue(4);
        assertThat(pause.waits).containsExactly(30_000L, 30_000L, 30_000L);
    }

    @Test
    void theAttemptsRunOutRatherThanWaitingForever() {
        RecordingPause pause = new RecordingPause();
        NodeWatchdog watchdog = new NodeWatchdog(3, 1_000, pause);
        AtomicInteger attempts = new AtomicInteger();

        assertThat(watchdog.awaitReachable(() -> {
            attempts.incrementAndGet();
            return false;
        })).isFalse();
        assertThat(attempts).hasValue(3);
        // Two waits for three attempts: nothing is waited for after the last one.
        assertThat(pause.waits).containsExactly(1_000L, 1_000L);
    }

    @Test
    void anUnreachableNodeThrowsRatherThanAnsweringFalse() {
        // Which is what a socket to nowhere does, so it has to count as unreachable rather than
        // escaping as an error nobody handles.
        NodeWatchdog watchdog = new NodeWatchdog(2, 1_000, new RecordingPause());

        assertThat(watchdog.awaitReachable(() -> {
            throw new RpcException(-32000, "connection refused");
        })).isFalse();
    }

    @Test
    void whileRunningOneFailureIsNotGivingUp() {
        NodeWatchdog watchdog = new NodeWatchdog(10, 1_000, new RecordingPause());

        assertThat(watchdog.recordAttempt(() -> false)).isTrue();
        assertThat(watchdog.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void enoughFailuresInARowIsGivingUp() {
        NodeWatchdog watchdog = new NodeWatchdog(3, 1_000, new RecordingPause());

        assertThat(watchdog.recordAttempt(() -> false)).isTrue();
        assertThat(watchdog.recordAttempt(() -> false)).isTrue();
        assertThat(watchdog.recordAttempt(() -> false)).isFalse();
    }

    @Test
    void oneSuccessForgivesEverythingBefore() {
        // A node that flaps should not accumulate its way to a shutdown over a week.
        NodeWatchdog watchdog = new NodeWatchdog(3, 1_000, new RecordingPause());
        AtomicBoolean answering = new AtomicBoolean(false);

        watchdog.recordAttempt(answering::get);
        watchdog.recordAttempt(answering::get);
        assertThat(watchdog.consecutiveFailures()).isEqualTo(2);

        answering.set(true);
        assertThat(watchdog.recordAttempt(answering::get)).isTrue();
        assertThat(watchdog.consecutiveFailures()).isZero();

        answering.set(false);
        assertThat(watchdog.recordAttempt(answering::get)).isTrue();
        assertThat(watchdog.recordAttempt(answering::get)).isTrue();
        assertThat(watchdog.recordAttempt(answering::get)).isFalse();
    }

    @Test
    void aWatchdogThatWouldNeverTryOrNeverWaitIsRefused() {
        assertThatThrownBy(() -> new NodeWatchdog(0, 1_000, new RecordingPause()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeWatchdog(10, 0, new RecordingPause()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
