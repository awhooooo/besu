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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import co.rsk.federate.rpc.EthClient;
import co.rsk.peg.BridgeAddresses;
import co.rsk.peg.BridgeEvents;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the bridge's events, a window of blocks at a time.
 *
 * <p>Nodes cap how many blocks one log query may span — Besu's default is 5,000 — so a search that
 * reaches further back is several queries, walking backwards from the head so that the most recent
 * answer is found first. Everything the sequencer looks for was emitted recently: a peg-out's
 * outpoint values are announced when it is built and wanted when it becomes signable, which the
 * confirmation wait puts inside a single window.
 */
public class BridgeEventReader {

    private static final Logger logger = LoggerFactory.getLogger(BridgeEventReader.class);

    private final EthClient node;
    private final int windowSize;

    public BridgeEventReader(EthClient node, int windowSize) {
        this.node = Objects.requireNonNull(node, "node");
        if (windowSize <= 0) {
            throw new IllegalArgumentException("A window must span at least one block");
        }
        this.windowSize = windowSize;
    }

    /** The topic that selects one event. */
    public static Bytes32 topicOf(BridgeEvents event) {
        return Bytes32.wrap(event.getEvent().encodeSignatureLong().getBytes());
    }

    /**
     * Searches backwards from {@code toBlock} for logs matching {@code topics}, stopping at the
     * first window that yields any.
     *
     * @param maxLookback how far back to go before giving up, in blocks
     */
    public List<EthClient.LogEntry> findBackwards(long toBlock, long maxLookback, List<List<Bytes32>> topics) {
        long floor = Math.max(0, toBlock - maxLookback + 1);
        long windowEnd = toBlock;

        while (windowEnd >= floor) {
            long windowStart = Math.max(floor, windowEnd - windowSize + 1);
            List<EthClient.LogEntry> found = node.logs(
                new EthClient.LogFilter(windowStart, windowEnd, BridgeAddresses.BRIDGE, topics));
            if (!found.isEmpty()) {
                logger.debug("[findBackwards] {} logs in blocks {}..{}", found.size(), windowStart, windowEnd);
                return found;
            }
            if (windowStart == floor) {
                break;
            }
            windowEnd = windowStart - 1;
        }

        logger.debug("[findBackwards] Nothing in blocks {}..{}", floor, toBlock);
        return List.of();
    }

    /** All logs matching {@code topics} in a range, asked for in windows. */
    public List<EthClient.LogEntry> findAll(long fromBlock, long toBlock, List<List<Bytes32>> topics) {
        List<EthClient.LogEntry> all = new ArrayList<>();
        long windowStart = Math.max(0, fromBlock);
        while (windowStart <= toBlock) {
            long windowEnd = Math.min(toBlock, windowStart + windowSize - 1);
            all.addAll(node.logs(
                new EthClient.LogFilter(windowStart, windowEnd, BridgeAddresses.BRIDGE, topics)));
            windowStart = windowEnd + 1;
        }
        return all;
    }
}
