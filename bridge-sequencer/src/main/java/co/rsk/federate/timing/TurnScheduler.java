/*
 * This file is part of RskJ
 * Copyright (C) 2018 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.federate.timing;

/**
 * Hands each federator a slot of its own in a repeating round.
 *
 * <p>Informing the bridge of a bitcoin block is work any member can do and only one needs to: the
 * rest would pay gas to be told the bridge already knows. Rather than coordinate, each member takes
 * the same turn every round, computed from the wall clock alone, so two members that never speak
 * still do not collide.
 */
public class TurnScheduler {

    private final int period;
    private final int participants;

    /**
     * @param period how long one slot lasts, in milliseconds
     * @param participants how many slots make up a round
     */
    public TurnScheduler(int period, int participants) {
        if (period <= 0) {
            throw new IllegalArgumentException("A period must be positive, got " + period);
        }
        if (participants <= 0) {
            throw new IllegalArgumentException("There must be at least one participant, got " + participants);
        }
        this.period = period;
        this.participants = participants;
    }

    /** How long a full round takes, which is how often any one participant's turn comes round. */
    public long getInterval() {
        return (long) participants * period;
    }

    /**
     * Whether the given position's slot is the one happening now.
     *
     * <p>Not the same question as {@link #getDelay}, which answers when a slot next starts. While a
     * position is inside its own slot, the next start is almost a full round away, so a small delay
     * means the turn is about to begin rather than that it is under way.
     */
    public boolean isTurnOf(long now, int position) {
        requirePosition(position);
        return Math.floorMod(now, getInterval()) / period == position;
    }

    /**
     * How long from {@code now} until the given position's next turn starts.
     *
     * @param position this participant's index in the round
     */
    public long getDelay(long now, int position) {
        requirePosition(position);

        long totalPeriod = getInterval();
        long slotStart = (long) position * period;
        long intoRound = Math.floorMod(now, totalPeriod);
        return Math.floorMod(slotStart - intoRound, totalPeriod);
    }

    private void requirePosition(int position) {
        if (position < 0 || position >= participants) {
            throw new IllegalArgumentException(
                String.format("Position must be between %d and %d, got %d", 0, participants - 1, position));
        }
    }
}
