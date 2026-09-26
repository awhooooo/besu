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
package co.rsk.federate.config;

/**
 * Sequencer-side policy, as opposed to the bridge's consensus constants.
 *
 * <p>Everything here is one operator's choice and can differ between members without any of them
 * disagreeing about the chain. Nothing in this file is read by the bridge, and changing any of it
 * needs no coordination. What a federator must agree on lives in {@code BridgeConstants} and is
 * fixed at genesis.
 */
public final class SequencerDefaults {

    private SequencerDefaults() {
    }

    /**
     * How long after the bridge says a peg-in is processed before the client stops tracking it,
     * in Besu blocks.
     *
     * <p>Not immediately, because the answer came from one node at one moment, and forgetting a
     * peg-in that was not really processed means never sending it again. RSK calls this
     * btc2RskMinimumAcceptableConfirmationsOnRsk and keeps it in its bridge constants, where it
     * does not belong: nothing in the bridge reads it.
     */
    public static final int MINIMUM_CONFIRMATIONS_ON_RSK = 3_600;

    /**
     * How long one federator's slot lasts, in milliseconds.
     *
     * <p>A round is this times the size of the federation, so with nine members a turn happens
     * somewhere every ninety seconds and each member acts every thirteen and a half minutes.
     */
    public static final int UPDATE_BRIDGE_EXECUTION_PERIOD_MS = 90_000;

    /** How many bitcoin headers to give the bridge in one turn. */
    public static final int AMOUNT_OF_HEADERS_TO_SEND = 100;

    /**
     * How many blocks one log query may span.
     *
     * <p>Besu refuses a wider one by default: {@code --rpc-max-logs-range} is 5,000. Staying under
     * it means the sequencer works against a node nobody had to reconfigure.
     */
    public static final int LOG_QUERY_WINDOW = 4_500;

    /**
     * How far back to look for a peg-out's outpoint values before giving up, in Besu blocks.
     *
     * <p>A peg-out's values are announced when it is built and needed when it becomes signable,
     * {@code rsk2BtcMinimumAcceptableConfirmations} blocks later. At 3,600 that is inside a single
     * window; this allows for a sequencer that was switched off across the wait.
     */
    public static final int MAX_LOG_LOOKBACK = 50_000;

    /** How often to look for a federation change. It happens perhaps twice in a chain's life. */
    public static final int WATCHER_PERIOD_MS = 120_000;

    /**
     * How often to sign whatever peg-outs are waiting.
     *
     * <p>Not a turn: every signature counts towards the threshold, so there is nothing redundant
     * to take turns over.
     */
    public static final int RELEASE_PERIOD_MS = 90_000;

    /** What a bridge transaction offers to pay, in wei per gas, once the bootstrap window is over. */
    public static final long GAS_PRICE = 1_000L;

    /** Enough for the largest bridge call: registering a transaction with its proof. */
    public static final long GAS_LIMIT = 4_000_000L;

    /**
     * How many times to find the node unreachable before giving up.
     *
     * <p>Giving up means exiting, so that whatever supervises the process can decide what to do.
     * Carrying on regardless would be a sequencer that looks alive and informs the bridge of
     * nothing.
     */
    public static final int NODE_UNREACHABLE_ATTEMPTS = 10;

    /** How long to wait between those attempts. */
    public static final int NODE_RETRY_PERIOD_MS = 30_000;

    /**
     * How long to remember having signed a peg-out, in minutes.
     *
     * <p>Between signing and the bridge counting the signature, the peg-out still appears to be
     * waiting, and signing it again costs another transaction to no effect.
     */
    public static final int PEGOUT_SIGNED_CACHE_TTL_MINUTES = 30;
}
