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

import java.time.Clock;
import java.util.Objects;

import co.rsk.federate.bitcoin.BitcoinWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Says where both chains are, now and then.
 *
 * <p>A sequencer spends most of its life doing nothing visible: waiting for its turn, finding
 * nothing to sign. Without a heartbeat there is no way to tell that from a process that has quietly
 * stopped working, and the first symptom would be a peg-in nobody relayed.
 *
 * <p>Rate-limited by time, because the alternative is a line every tick that nobody reads.
 */
public class SequencerLogger {

    private static final Logger logger = LoggerFactory.getLogger(SequencerLogger.class);

    private final BitcoinWrapper bitcoinWrapper;
    private final FederatorSupport federatorSupport;
    private final Clock clock;
    private final long minMillisBetweenLogs;

    private boolean hasLogged;
    private long lastLoggedAt;

    public SequencerLogger(
        BitcoinWrapper bitcoinWrapper,
        FederatorSupport federatorSupport,
        Clock clock,
        long minMillisBetweenLogs) {
        this.bitcoinWrapper = Objects.requireNonNull(bitcoinWrapper, "bitcoinWrapper");
        this.federatorSupport = Objects.requireNonNull(federatorSupport, "federatorSupport");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (minMillisBetweenLogs < 0) {
            throw new IllegalArgumentException("A gap cannot be negative, got " + minMillisBetweenLogs);
        }
        this.minMillisBetweenLogs = minMillisBetweenLogs;
    }

    /** Logs if enough time has passed since the last time. */
    public void log() {
        long now = clock.millis();
        // A flag rather than a sentinel timestamp: subtracting Long.MIN_VALUE from a real epoch
        // millisecond overflows to a negative number, which would silently mean "too soon" forever.
        if (hasLogged && now - lastLoggedAt < minMillisBetweenLogs) {
            return;
        }
        hasLogged = true;
        lastLoggedAt = now;

        try {
            logger.info("[log] bitcoin at {}, bridge's bitcoin at {}, besu at {}",
                bitcoinWrapper.getBestChainHeight(),
                federatorSupport.getBtcBestBlockChainHeight(),
                federatorSupport.getRskBestChainHeight());
        } catch (Exception e) {
            // The heartbeat must never be the reason a tick fails.
            logger.warn("[log] Could not read where the chains are: {}", e.getMessage());
        }
    }
}
