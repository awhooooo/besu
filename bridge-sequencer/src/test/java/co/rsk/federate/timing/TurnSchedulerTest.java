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
    void exactlyOneParticipantIsTakingItsTurnAtAnyInstant() {
        TurnScheduler scheduler = new TurnScheduler(90_000, 9);

        for (long tick = 0; tick < 1_620_000; tick += 7_919) {
            final long now = tick;
            long taking = java.util.stream.IntStream.range(0, 9)
                .filter(position -> scheduler.isTurnOf(now, position))
                .count();
            assertThat(taking).as("at t=%d", now).isEqualTo(1);
        }
    }

    @Test
    void aTurnLastsItsWholeSlotAndNotAMomentLonger() {
        TurnScheduler scheduler = new TurnScheduler(1_000, 4);

        // Position 1 holds [1000, 2000); position 2 takes over at exactly 2000.
        assertThat(scheduler.isTurnOf(1_999, 1)).isTrue();
        assertThat(scheduler.isTurnOf(2_000, 1)).isFalse();
        assertThat(scheduler.isTurnOf(1_999, 2)).isFalse();
        assertThat(scheduler.isTurnOf(2_000, 2)).isTrue();
        assertThat(scheduler.isTurnOf(2_999, 2)).isTrue();
        assertThat(scheduler.isTurnOf(3_000, 2)).isFalse();
    }

    @Test
    void theTurnAndTheDelayAnswerDifferentQuestions() {
        // Being inside a slot means the next start of that slot is nearly a round away, so a
        // small delay means a turn is about to begin rather than that one is under way.
        TurnScheduler scheduler = new TurnScheduler(1_000, 4);

        assertThat(scheduler.isTurnOf(2_500, 2)).isTrue();
        assertThat(scheduler.getDelay(2_500, 2)).isEqualTo(3_500L);
    }

    @Test
    void aTurnBeforeTheEpochStillBelongsToSomebody() {
        TurnScheduler scheduler = new TurnScheduler(1_000, 4);

        long taking = java.util.stream.IntStream.range(0, 4)
            .filter(position -> scheduler.isTurnOf(-1, position))
            .count();
        assertThat(taking).isEqualTo(1);
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
