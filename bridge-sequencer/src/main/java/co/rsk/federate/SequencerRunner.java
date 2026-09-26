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

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import co.rsk.federate.bitcoin.BitcoinWrapper;
import co.rsk.federate.bitcoin.BitcoinWrapperImpl;
import co.rsk.federate.bitcoin.Kit;
import co.rsk.federate.btcreleaseclient.BtcReleaseClient;
import co.rsk.federate.btcreleaseclient.PegoutSignedCacheImpl;
import co.rsk.federate.config.SequencerConfig;
import co.rsk.federate.io.BtcToRskClientFileStorageImpl;
import co.rsk.federate.io.BtcToRskClientFileStorageInfo;
import co.rsk.federate.rpc.JsonRpcEthClient;
import co.rsk.federate.signing.ECDSACompositeSigner;
import co.rsk.federate.signing.ECDSASigner;
import co.rsk.federate.signing.ECDSASignerFactory;
import co.rsk.federate.signing.SequencerKeyId;
import co.rsk.federate.signing.SignerException;
import co.rsk.federate.signing.config.SignerConfig;
import co.rsk.federate.tx.LegacyTransactionSigner;
import co.rsk.federate.watcher.FederationWatcher;
import co.rsk.federate.watcher.FederationWatcherListener;
import co.rsk.federate.watcher.FederationWatcherListenerImpl;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.host.BridgePrecompiles;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.PeerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a sequencer out of a configuration file and starts it.
 *
 * <p>The order matters in two places. The keys are checked before anything is connected, because a
 * missing or world-readable key file should be a refusal to start rather than a failure discovered
 * on the first turn. And the node is waited for before the federations are read, because the
 * federations come from the bridge and there is nothing to point a client at until they do.
 *
 * <p>Two peg-in clients, because a federation change puts two bitcoin addresses in play and each
 * keeps its own record of the proofs it has gathered. One release client, which holds whichever
 * federations exist and signs only for those its keys belong to.
 */
public class SequencerRunner implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SequencerRunner.class);

    private final SequencerConfig config;

    private BitcoinWrapper bitcoinWrapper;
    private SequencerScheduler scheduler;
    private boolean closed;
    private volatile Runnable onStopped = () -> { };

    public SequencerRunner(SequencerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * What to do once the sequencer has stopped on its own, as opposed to being killed.
     *
     * <p>Normally ending the process with a failing status, so that whatever supervises it can
     * tell this apart from an ordinary shutdown and start it again.
     */
    public void onStopped(Runnable action) {
        this.onStopped = Objects.requireNonNull(action, "action");
    }

    /**
     * Reads the configuration and the keys and says what is wrong, without connecting to anything.
     *
     * @return the problems found, empty if there are none
     */
    public List<String> check() {
        List<String> problems = new ArrayList<>();
        try {
            ECDSASigner signer = buildSigner();
            problems.addAll(signer.check());
            for (SequencerKeyId key : SequencerKeyId.values()) {
                if (!signer.canSignWith(key.getKeyId())) {
                    problems.add("No signer can use the " + key + " key");
                }
            }
        } catch (RuntimeException e) {
            problems.add("Could not build the signers: " + e.getMessage());
        }
        BridgeConstants bridgeConstants;
        try {
            bridgeConstants = BridgePrecompiles.constantsFor(config.network());
        } catch (RuntimeException e) {
            // Nothing after this can be checked without knowing the network, and repeating the
            // same complaint under a second heading would only bury it.
            problems.add(e.getMessage());
            return problems;
        }
        try {
            peerAddresses(bridgeConstants);
        } catch (RuntimeException e) {
            problems.add("Bitcoin peers: " + e.getMessage());
        }
        return problems;
    }

    /** Builds everything and starts the timers. Returns once they are running. */
    public void start() throws Exception {
        List<String> problems = check();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                "Refusing to start:\n  " + String.join("\n  ", problems));
        }

        BridgeConstants bridgeConstants = BridgePrecompiles.constantsFor(config.network());
        ECDSASigner signer = buildSigner();
        logger.info("[start] Signing with {}", signer.describe());

        JsonRpcEthClient node = new JsonRpcEthClient(URI.create(config.besuUrl()), Duration.ofSeconds(30));
        BridgeClient bridge = new BridgeClient(
            node,
            new LegacyTransactionSigner(
                signer, SequencerKeyId.RSK.getKeyId(), BigInteger.valueOf(config.chainId())),
            new GasPolicy(config.bridgeTxsPaidFromBlock(), config.gasPrice()),
            config.gasLimit());

        NodeWatchdog watchdog =
            NodeWatchdog.sleeping(config.nodeUnreachableAttempts(), config.nodeRetryPeriodMs());
        if (!watchdog.awaitReachable(bridge::nodeIsUsable)) {
            throw new IllegalStateException(String.format(
                "The node at %s did not answer in %d attempts. Nothing can be read from the bridge "
                    + "until it does, so there is no point carrying on.",
                config.besuUrl(), config.nodeUnreachableAttempts()));
        }

        FederatorSupport federatorSupport = new FederatorSupport(bridge, bridgeConstants.getBtcParams());
        logger.info("[start] Sending from {}", federatorSupport.senderAddress());

        // Before the bitcoin peer, because how far back it has to look is decided by when the
        // oldest federation it will watch came into being.
        FederationProvider federationProvider = new FederationProvider(bridge, bridgeConstants);
        bitcoinWrapper = startBitcoinPeer(bridgeConstants, earliestRelevantTime(federationProvider));

        BridgeEventReader events = new BridgeEventReader(node, config.logQueryWindow());
        PegoutOutpointValues outpointValues = new PegoutOutpointValues(events, config.maxLogLookback());

        BtcToRskClient activeClient = peginClient(bridgeConstants, federatorSupport, "active");
        BtcToRskClient retiringClient = peginClient(bridgeConstants, federatorSupport, "retiring");
        BtcReleaseClient releaseClient = new BtcReleaseClient(
            bitcoinWrapper,
            federatorSupport,
            bridgeConstants,
            signer,
            outpointValues,
            events,
            new PegoutSignedCacheImpl(
                Duration.ofMinutes(config.pegoutSignedCacheTtlMinutes()), Clock.systemUTC()),
            config.logQueryWindow());

        FederationWatcherListener listener = new FederationWatcherListenerImpl(
            activeClient, retiringClient, releaseClient, bitcoinWrapper);
        FederationWatcher watcher = new FederationWatcher(federationProvider, listener);

        SequencerLogger heartbeat = new SequencerLogger(
            bitcoinWrapper, federatorSupport, Clock.systemUTC(), config.watcherPeriodMs());

        scheduler = new SequencerScheduler(
            watcher,
            List.of(activeClient, retiringClient),
            releaseClient,
            signer,
            watchdog,
            bridge,
            heartbeat,
            Clock.systemUTC(),
            config.turnPeriodMs(),
            config.watcherPeriodMs(),
            config.releasePeriodMs());
        // Not on the scheduler's own thread: closing shuts that pool down, which interrupts the
        // thread doing the closing, and the bitcoin peer would then be asked to stop while
        // interrupted. A thread of its own can take its time.
        scheduler.onNodeLost(() -> new Thread(() -> {
            close();
            onStopped.run();
        }, "sequencer-node-lost").start());
        scheduler.start();
        logger.info("[start] Running");
    }

    /**
     * Stops everything, once.
     *
     * <p>Two things call this and they can arrive together: the shutdown hook when the process is
     * being killed, and the watchdog when the node has been gone too long. Closing twice would
     * have one caller shutting the bitcoin peer down while the other read a field the first had
     * already cleared.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (scheduler != null) {
            scheduler.stop();
            scheduler = null;
        }
        if (bitcoinWrapper != null) {
            try {
                bitcoinWrapper.stop();
            } catch (RuntimeException e) {
                logger.warn("[close] The bitcoin peer did not stop cleanly: {}", e.getMessage());
            }
            bitcoinWrapper = null;
        }
        logger.info("[close] Stopped");
    }

    /** True once {@link #close} has run, so a caller can tell a clean stop from a crash. */
    public synchronized boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------ pieces

    private ECDSASigner buildSigner() {
        ECDSACompositeSigner composite = new ECDSACompositeSigner();
        ECDSASignerFactory factory = new ECDSASignerFactory();
        for (SignerConfig signerConfig : config.signers()) {
            try {
                composite.addSigner(factory.buildFromConfig(signerConfig));
            } catch (SignerException e) {
                throw new IllegalStateException(
                    "Could not build the " + signerConfig.getId() + " signer: " + e.getMessage(), e);
            }
        }
        return composite;
    }

    private BitcoinWrapper startBitcoinPeer(BridgeConstants bridgeConstants, long earliestRelevantTime) {
        org.bitcoinj.core.NetworkParameters params =
            co.rsk.federate.adapter.ThinConverter.toOriginal(bridgeConstants.getBtcParamsString());
        Context btcContext = new Context(params);
        Path bitcoinDir = config.databaseDir().resolve("bitcoin");
        Kit kit = new Kit(btcContext, bitcoinDir.toFile(), "sequencer", earliestRelevantTime);

        BitcoinWrapperImpl wrapper = new BitcoinWrapperImpl(btcContext, kit);
        wrapper.setup(peerAddresses(bridgeConstants));
        wrapper.start();
        logger.info("[startBitcoinPeer] Bitcoin peer started in {}, looking back to {}",
            bitcoinDir, java.time.Instant.ofEpochSecond(earliestRelevantTime));
        return wrapper;
    }

    /**
     * The earliest moment on bitcoin that could hold anything this sequencer cares about.
     *
     * <p>A federation's address has no history before the federation existed, so there is nothing
     * to find further back than the oldest one still live. The number matters more than it looks:
     * bitcoinj takes the minimum of this and what the wallet already knows, uses it to pick a
     * checkpoint to start the block store from, and skips it entirely when it is zero — which is
     * how a sequencer ends up downloading and filtering the whole chain, and why bitcoinj logs
     * "this will result in a very slow chain sync" when it happens.
     *
     * <p>A day of margin, because being early costs a little scanning and being late costs a
     * peg-in that is never seen.
     */
    private long earliestRelevantTime(FederationProvider federationProvider) {
        long active = federationProvider.getActiveFederation().getCreationTime().getEpochSecond();
        long oldest = federationProvider.getRetiringFederation()
            .map(federation -> Math.min(active, federation.getCreationTime().getEpochSecond()))
            .orElse(active);
        return withMargin(oldest);
    }

    /** One day earlier, and never before the epoch. */
    static long withMargin(long epochSeconds) {
        return Math.max(1L, epochSeconds - Duration.ofDays(1).toSeconds());
    }

    private List<PeerAddress> peerAddresses(BridgeConstants bridgeConstants) {
        org.bitcoinj.core.NetworkParameters params =
            co.rsk.federate.adapter.ThinConverter.toOriginal(bridgeConstants.getBtcParamsString());
        List<PeerAddress> addresses = new ArrayList<>();
        for (String peer : config.bitcoinPeerAddresses()) {
            int colon = peer.lastIndexOf(':');
            String host = colon < 0 ? peer : peer.substring(0, colon);
            int port = colon < 0 ? params.getPort() : Integer.parseInt(peer.substring(colon + 1));
            InetSocketAddress resolved = new InetSocketAddress(host, port);
            if (resolved.isUnresolved()) {
                throw new IllegalArgumentException("Cannot resolve bitcoin peer " + peer);
            }
            addresses.add(new PeerAddress(params, resolved.getAddress(), port));
        }
        return addresses;
    }

    private BtcToRskClient peginClient(
        BridgeConstants bridgeConstants, FederatorSupport federatorSupport, String name) throws IOException {
        return new BtcToRskClient(
            bitcoinWrapper,
            federatorSupport,
            bridgeConstants,
            new BtcToRskClientFileStorageImpl(
                new BtcToRskClientFileStorageInfo(config.databaseDir().resolve(name))),
            new BtcLockSenderProvider(),
            new PeginInstructionsProvider(),
            config.amountOfHeadersToSend(),
            config.minimumConfirmationsOnRsk());
    }
}
