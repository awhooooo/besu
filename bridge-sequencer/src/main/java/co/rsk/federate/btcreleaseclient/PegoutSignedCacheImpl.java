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
package co.rsk.federate.btcreleaseclient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.hyperledger.besu.datatypes.Hash;

/** A time-limited memory of what has been signed, swept as it is used. */
public class PegoutSignedCacheImpl implements PegoutSignedCache {

    private final Map<Hash, Instant> signedAt = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public PegoutSignedCacheImpl(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("A time to live must be positive, got " + ttl);
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean hasAlreadyBeenSigned(Hash pegoutCreationRskTxHash) {
        if (pegoutCreationRskTxHash == null) {
            return false;
        }
        sweep();
        return signedAt.containsKey(pegoutCreationRskTxHash);
    }

    @Override
    public void put(Hash pegoutCreationRskTxHash) {
        Objects.requireNonNull(pegoutCreationRskTxHash, "pegoutCreationRskTxHash");
        sweep();
        signedAt.put(pegoutCreationRskTxHash, clock.instant());
    }

    private void sweep() {
        Instant cutoff = clock.instant().minus(ttl);
        Iterator<Map.Entry<Hash, Instant>> entries = signedAt.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().isBefore(cutoff)) {
                entries.remove();
            }
        }
    }
}
