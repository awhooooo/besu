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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.federate.rpc.EthClient;
import co.rsk.peg.BridgeEvents;
import co.rsk.peg.bitcoin.UtxoUtils;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What each input of a peg-out was worth.
 *
 * <p>A segwit signature commits to the value of the output being spent, and a peg-out arrives from
 * the bridge as bytes. Bytes carry outpoints but not what those outpoints held, so the values have
 * to come from somewhere else: the bridge announces them in a {@code pegout_transaction_created}
 * event when it builds the peg-out.
 *
 * <p>That announcement is {@code rsk2BtcMinimumAcceptableConfirmations} blocks before the peg-out
 * can be signed, which at 3,600 sits inside a single log window, so the usual case is one query.
 * A sequencer that was switched off across the wait searches further back, a window at a time.
 *
 * <p>Answers are kept in memory only. Re-reading them costs one query, whereas a file would have
 * to be kept correct across restarts to save it.
 */
public class PegoutOutpointValues {

    private static final Logger logger = LoggerFactory.getLogger(PegoutOutpointValues.class);

    private final BridgeEventReader events;
    private final long maxLookback;
    private final Map<Sha256Hash, Announcement> known = new HashMap<>();

    public PegoutOutpointValues(BridgeEventReader events, long maxLookback) {
        this.events = Objects.requireNonNull(events, "events");
        if (maxLookback <= 0) {
            throw new IllegalArgumentException("A lookback must span at least one block");
        }
        this.maxLookback = maxLookback;
    }

    /**
     * What the bridge said when it built a peg-out.
     *
     * @param outpointValues what each input was worth, in input order
     * @param blockNumber the block the announcement was in, which is when the peg-out was built
     */
    public record Announcement(List<Coin> outpointValues, long blockNumber) {
        public Announcement {
            outpointValues = List.copyOf(outpointValues);
        }
    }

    /**
     * The value of each input of this peg-out, in input order.
     *
     * @param btcTxHash the peg-out's hash as the bridge announced it, which for a segwit peg-out
     *     is also the hash it has now, since a txid does not cover the witness
     * @param chainHeight where to start searching back from
     */
    public Optional<List<Coin>> valuesFor(Sha256Hash btcTxHash, long chainHeight) {
        return announcementFor(btcTxHash, chainHeight).map(Announcement::outpointValues);
    }

    /**
     * The whole announcement, including when it was made.
     *
     * <p>When matters for the validation spend, which may only be signed once it has been waiting
     * as long as any other peg-out. Powpeg found that out by walking the chain for the receipt;
     * the log that carries the values carries its own block number.
     */
    public synchronized Optional<Announcement> announcementFor(Sha256Hash btcTxHash, long chainHeight) {
        Announcement cached = known.get(btcTxHash);
        if (cached != null) {
            return Optional.of(cached);
        }

        List<List<Bytes32>> topics = List.of(
            List.of(BridgeEventReader.topicOf(BridgeEvents.PEGOUT_TRANSACTION_CREATED)),
            List.of(Bytes32.wrap(btcTxHash.getBytes())));

        List<EthClient.LogEntry> found = events.findBackwards(chainHeight, maxLookback, topics);
        if (found.isEmpty()) {
            logger.warn(
                "[announcementFor] The bridge announced no outpoint values for peg-out {} in the last {} blocks. "
                    + "Its segwit inputs cannot be signed until they are found.",
                btcTxHash, maxLookback);
            return Optional.empty();
        }

        EthClient.LogEntry log = found.get(0);
        byte[] encoded =
            (byte[]) BridgeEvents.PEGOUT_TRANSACTION_CREATED.getEvent().decodeEventData(log.data())[0];
        Announcement announcement =
            new Announcement(UtxoUtils.decodeOutpointValues(encoded), log.blockNumber());
        logger.debug("[announcementFor] Peg-out {} spends {} outpoints, built in block {}",
            btcTxHash, announcement.outpointValues().size(), announcement.blockNumber());
        known.put(btcTxHash, announcement);
        return Optional.of(announcement);
    }

    /** Used by tests and by logging; the cache is an optimisation, not state anything depends on. */
    synchronized int cachedCount() {
        return known.size();
    }
}
