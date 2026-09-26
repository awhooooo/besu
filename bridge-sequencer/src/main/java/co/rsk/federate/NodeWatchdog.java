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
package co.rsk.federate;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides how long to put up with a node that will not answer.
 *
 * <p>A node restarting is ordinary and worth waiting out; a node that is gone is not. The
 * difference is only visible over time, so failures are counted and the count is reset by any
 * success. At the limit the sequencer stops rather than carrying on: a process that looks alive
 * while informing the bridge of nothing is worse than one that is plainly dead, because whatever
 * supervises it can restart the second.
 *
 * <p>Sleeping is injected so that a test does not have to wait.
 */
public class NodeWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(NodeWatchdog.class);

    /** What to do between attempts. Real code sleeps; a test counts. */
    public interface Pause {
        void forMillis(long millis) throws InterruptedException;
    }

    private final int allowedFailures;
    private final long retryPeriodMs;
    private final Pause pause;

    private int consecutiveFailures;

    public NodeWatchdog(int allowedFailures, long retryPeriodMs, Pause pause) {
        if (allowedFailures <= 0) {
            throw new IllegalArgumentException("There must be at least one attempt, got " + allowedFailures);
        }
        if (retryPeriodMs <= 0) {
            throw new IllegalArgumentException("A retry period must be positive, got " + retryPeriodMs);
        }
        this.allowedFailures = allowedFailures;
        this.retryPeriodMs = retryPeriodMs;
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /** A watchdog that really sleeps. */
    public static NodeWatchdog sleeping(int allowedFailures, long retryPeriodMs) {
        return new NodeWatchdog(allowedFailures, retryPeriodMs, Thread::sleep);
    }

    /**
     * Waits for the node to answer, for as many attempts as are allowed.
     *
     * <p>Used at startup, where there is nothing to do until it does: the federation has to be read
     * from the bridge before any client can be pointed at it.
     *
     * @return true if the node answered, false if the attempts ran out
     */
    public boolean awaitReachable(BooleanSupplier reachable) {
        for (int attempt = 1; attempt <= allowedFailures; attempt++) {
            if (isReachable(reachable)) {
                logger.info("[awaitReachable] The node answered on attempt {}", attempt);
                consecutiveFailures = 0;
                return true;
            }
            logger.warn("[awaitReachable] The node did not answer, attempt {} of {}", attempt, allowedFailures);
            if (attempt == allowedFailures) {
                break;
            }
            try {
                pause.forMillis(retryPeriodMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("[awaitReachable] Interrupted while waiting for the node");
                return false;
            }
        }
        return false;
    }

    /**
     * Records one attempt made while running.
     *
     * @return true to carry on, false once the node has failed to answer too many times in a row
     */
    public boolean recordAttempt(BooleanSupplier reachable) {
        if (isReachable(reachable)) {
            if (consecutiveFailures > 0) {
                logger.info("[recordAttempt] The node is answering again after {} failures", consecutiveFailures);
            }
            consecutiveFailures = 0;
            return true;
        }

        consecutiveFailures++;
        if (consecutiveFailures < allowedFailures) {
            logger.warn("[recordAttempt] The node did not answer, {} of {} in a row",
                consecutiveFailures, allowedFailures);
            return true;
        }

        logger.error(
            "[recordAttempt] The node has not answered {} times in a row. Stopping: a sequencer that "
                + "cannot reach its node informs the bridge of nothing, and looking alive while doing so "
                + "hides the problem from whatever could fix it.",
            consecutiveFailures);
        return false;
    }

    /** How many attempts in a row have failed, for logging and for tests. */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    private static boolean isReachable(BooleanSupplier reachable) {
        try {
            return reachable.getAsBoolean();
        } catch (RuntimeException e) {
            // An unreachable node throws rather than answering false.
            logger.debug("[isReachable] {}", e.getMessage());
            return false;
        }
    }
}
