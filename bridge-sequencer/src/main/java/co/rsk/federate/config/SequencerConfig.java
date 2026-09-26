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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import co.rsk.federate.signing.SequencerKeyId;
import co.rsk.federate.signing.config.SignerConfig;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;

/**
 * Everything one operator has to decide, read from a file.
 *
 * <p>Read once at startup and then fixed. A sequencer that re-read its configuration while running
 * could change which key it signs with between one turn and the next, and nothing downstream is
 * built to notice.
 *
 * <p>Absent values fall back to {@link SequencerDefaults}. The ones with no sensible default are
 * the ones that are specific to this operator or this chain: where the node is, which network,
 * where the keys are, where to keep files.
 */
public class SequencerConfig {

    private static final String ROOT = "sequencer";

    private final Config config;

    SequencerConfig(Config config) {
        this.config = Objects.requireNonNull(config, "config").getConfig(ROOT);
    }

    /** Reads a HOCON file, refusing one that is not there rather than running on defaults alone. */
    public static SequencerConfig from(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException("No readable configuration file at " + file.toAbsolutePath());
        }
        Config parsed = ConfigFactory.parseFile(
            file.toFile(),
            ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false));
        return new SequencerConfig(parsed.resolve());
    }

    /** For tests, and for anything that already holds a parsed configuration. */
    public static SequencerConfig of(Config config) {
        return new SequencerConfig(config);
    }

    // ------------------------------------------------------------------ the node

    /** Where the Besu node's JSON-RPC is. */
    public String besuUrl() {
        return config.getString("besu.url");
    }

    /**
     * The chain this sequencer signs for.
     *
     * <p>Part of every signature, so a wrong one produces transactions that are valid on some
     * other chain and refused on this one.
     */
    public long chainId() {
        return config.getLong("besu.chainId");
    }

    public long gasLimit() {
        return optionalLong("besu.gasLimit").orElse(SequencerDefaults.GAS_LIMIT);
    }

    public java.math.BigInteger gasPrice() {
        return java.math.BigInteger.valueOf(
            optionalLong("besu.gasPrice").orElse(SequencerDefaults.GAS_PRICE));
    }

    /**
     * The first block at which bridge transactions must pay.
     *
     * <p>Consensus, and the same number the chain's genesis file carries. Getting it wrong here
     * only costs this sequencer: too low and it offers a fee nobody required, too high and it
     * offers none when one is due and the transaction is refused.
     */
    public long bridgeTxsPaidFromBlock() {
        return config.getLong("bridgeTxsPaidFromBlock");
    }

    // ------------------------------------------------------------------ bitcoin

    /** Which bitcoin network the bridge is pegged to: mainnet, testnet or regtest. */
    public String network() {
        return config.getString("network");
    }

    /**
     * The bitcoin peers to connect to.
     *
     * <p>Given explicitly rather than discovered, because this chain's bitcoin peer is expected to
     * be one the operator runs. An empty list would leave bitcoinj to find peers on its own, which
     * is not what anybody wants here, so it is refused.
     */
    public List<String> bitcoinPeerAddresses() {
        List<String> peers = config.getStringList("bitcoinPeerAddresses");
        if (peers.isEmpty()) {
            throw new IllegalArgumentException(
                "sequencer.bitcoinPeerAddresses is empty; name the bitcoin node this sequencer should use");
        }
        return List.copyOf(peers);
    }

    // ------------------------------------------------------------------ files and keys

    /** Where the peg-in clients keep the proofs they have gathered. */
    public Path databaseDir() {
        return Path.of(config.getString("databaseDir"));
    }

    /**
     * How each of the three keys is held.
     *
     * <p>All three are required even though MST signs nothing on this chain, because a federation
     * member is registered with three and the bridge reports three. One file or one device per
     * key, never shared.
     */
    public SignerConfig signerFor(SequencerKeyId key) {
        String path = "signers." + key.getKeyId().getId();
        if (!config.hasPath(path)) {
            throw new IllegalArgumentException(String.format(
                "No signer configured at sequencer.%s. All of %s are required.",
                path, java.util.Arrays.toString(SequencerKeyId.values())));
        }
        return new SignerConfig(key.getKeyId().getId(), config.getConfig(path));
    }

    public List<SignerConfig> signers() {
        List<SignerConfig> all = new ArrayList<>(SequencerKeyId.values().length);
        for (SequencerKeyId key : SequencerKeyId.values()) {
            all.add(signerFor(key));
        }
        return all;
    }

    // ------------------------------------------------------------------ cadences

    /** How long one federator's slot lasts. A round is this times the size of the federation. */
    public int turnPeriodMs() {
        return optionalInt("turnPeriodMs").orElse(SequencerDefaults.UPDATE_BRIDGE_EXECUTION_PERIOD_MS);
    }

    /** How often to look for a federation change. Independent of the turn. */
    public int watcherPeriodMs() {
        return optionalInt("watcherPeriodMs").orElse(SequencerDefaults.WATCHER_PERIOD_MS);
    }

    /** How often to sign whatever peg-outs are waiting. Not turn-scheduled: every signature counts. */
    public int releasePeriodMs() {
        return optionalInt("releasePeriodMs").orElse(SequencerDefaults.RELEASE_PERIOD_MS);
    }

    public int amountOfHeadersToSend() {
        return optionalInt("amountOfHeadersToSend").orElse(SequencerDefaults.AMOUNT_OF_HEADERS_TO_SEND);
    }

    public int minimumConfirmationsOnRsk() {
        return optionalInt("minimumConfirmationsOnRsk").orElse(SequencerDefaults.MINIMUM_CONFIRMATIONS_ON_RSK);
    }

    public int logQueryWindow() {
        return optionalInt("logQueryWindow").orElse(SequencerDefaults.LOG_QUERY_WINDOW);
    }

    public int maxLogLookback() {
        return optionalInt("maxLogLookback").orElse(SequencerDefaults.MAX_LOG_LOOKBACK);
    }

    public int pegoutSignedCacheTtlMinutes() {
        return optionalInt("pegoutSignedCacheTtlMinutes")
            .orElse(SequencerDefaults.PEGOUT_SIGNED_CACHE_TTL_MINUTES);
    }

    // ------------------------------------------------------------------ the node going away

    /** How many times to find the node unreachable before giving up. */
    public int nodeUnreachableAttempts() {
        return optionalInt("nodeUnreachableAttempts").orElse(SequencerDefaults.NODE_UNREACHABLE_ATTEMPTS);
    }

    /** How long to wait between those attempts. */
    public int nodeRetryPeriodMs() {
        return optionalInt("nodeRetryPeriodMs").orElse(SequencerDefaults.NODE_RETRY_PERIOD_MS);
    }

    private Optional<Integer> optionalInt(String path) {
        return config.hasPath(path) ? Optional.of(config.getInt(path)) : Optional.empty();
    }

    private Optional<Long> optionalLong(String path) {
        return config.hasPath(path) ? Optional.of(config.getLong(path)) : Optional.empty();
    }
}
