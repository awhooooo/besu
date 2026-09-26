package co.rsk.federate.timing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The whole point of the scheduler is that federators who never talk to each other still take
 * different turns, so these check the property rather than particular numbers: at any instant, the
 * participants' next turns are all different and cover the round exactly once.
 */
class TurnSchedulerTest {

    @Test
    void aRoundIsOneSlotPerParticipant() {
        assertThat(new TurnScheduler(90_000, 9).getInterval()).isEqualTo(810_000L);
        assertThat(new TurnScheduler(90_000, 1).getInterval()).isEqualTo(90_000L);
    }

    @Test
    void noTwoParticipantsShareASlot() {
        TurnScheduler scheduler = new TurnScheduler(90_000, 9);
        for (long now = 0; now < 810_000; now += 7_919) {
            Set<Long> delays = new HashSet<>();
            for (int position = 0; position < 9; position++) {
                delays.add(scheduler.getDelay(now, position));
            }
            assertThat(delays).as("at t=%d", now).hasSize(9);
        }
    }

    @Test
    void everyDelayLandsWithinOneRound() {
        TurnScheduler scheduler = new TurnScheduler(90_000, 9);
        for (long now = 0; now < 2_000_000; now += 4_001) {
            for (int position = 0; position < 9; position++) {
                assertThat(scheduler.getDelay(now, position)).isBetween(0L, 809_999L);
            }
        }
    }

    @Test
    void aTurnStartsExactlyOnItsSlot() {
        TurnScheduler scheduler = new TurnScheduler(1_000, 4);
        // At the top of the round, position 0 goes now and the others wait their slots out.
        assertThat(scheduler.getDelay(0, 0)).isZero();
        assertThat(scheduler.getDelay(0, 1)).isEqualTo(1_000L);
        assertThat(scheduler.getDelay(0, 3)).isEqualTo(3_000L);
        // One millisecond into position 0's slot, its next turn is a full round away.
        assertThat(scheduler.getDelay(1, 0)).isEqualTo(3_999L);
    }

    @Test
    void theClockNeedNotStartAtZero() {
        TurnScheduler scheduler = new TurnScheduler(1_000, 4);
        // A real epoch millisecond, to be sure nothing assumes a small or aligned clock.
        long now = 1_790_000_123_456L;
        for (int position = 0; position < 4; position++) {
            long delay = scheduler.getDelay(now, position);
            assertThat((now + delay) % 4_000).isEqualTo(position * 1_000L);
        }
    }

    @Test
    void aNegativeClockStillAllocatesSlots() {
        // Math.floorMod rather than %, which would hand out a negative delay before the epoch.
        TurnScheduler scheduler = new TurnScheduler(1_000, 4);
        for (int position = 0; position < 4; position++) {
            assertThat(scheduler.getDelay(-1, position)).isNotNegative();
        }
    }

    @Test
    void aPositionOutsideTheRoundIsRejected() {
        TurnScheduler scheduler = new TurnScheduler(1_000, 4);
        assertThatThrownBy(() -> scheduler.getDelay(0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scheduler.getDelay(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRoundNeedsAPeriodAndAParticipant() {
        assertThatThrownBy(() -> new TurnScheduler(0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnScheduler(1_000, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLargeFederationDoesNotOverflowTheRound() {
        // participants * period as ints would overflow; the interval is a long.
        TurnScheduler scheduler = new TurnScheduler(Integer.MAX_VALUE, 100);
        assertThat(scheduler.getInterval()).isEqualTo(100L * Integer.MAX_VALUE);
        assertThat(scheduler.getDelay(0, 99)).isEqualTo(99L * Integer.MAX_VALUE);
    }
}
