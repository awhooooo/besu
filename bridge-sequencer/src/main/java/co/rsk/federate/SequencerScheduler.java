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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.federate.btcreleaseclient.BtcReleaseClient;
import co.rsk.federate.signing.ECDSASigner;
import co.rsk.federate.signing.SequencerKeyId;
import co.rsk.federate.signing.SignerException;
import co.rsk.federate.timing.TurnScheduler;
import co.rsk.federate.watcher.FederationWatcher;
import co.rsk.peg.federation.Federation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only thing here that decides when anything happens.
 *
 * <p>Powpeg put a timer inside each client. Keeping the cadences in one place means they can be
 * read together, and it leaves the clients as things you call: every one of the sequencer's tests
 * drives them directly, which is only possible because they do not schedule themselves.
 *
 * <p>Three cadences, because the work is three different shapes.
 *
 * <p>Informing the bridge about bitcoin is work any member can do and only one needs to, so it is
 * taken in turns: a slot each, computed from the clock alone, so that members who never speak to
 * each other still do not all pay to say the same thing. The slot is recomputed when the
 * federation changes, since both the number of participants and this member's place in it move.
 *
 * <p>Signing peg-outs is not like that. Every signature counts towards the threshold, so there is
 * nothing redundant and no turn to take; it runs on every tick.
 *
 * <p>Looking for a federation change is its own slow tick. Three cheap calls, and a change happens
 * perhaps twice in a chain's life.
 */
public class SequencerScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SequencerScheduler.class);

    private final FederationWatcher watcher;
    private final List<BtcToRskClient> peginClients;
    private final BtcReleaseClient releaseClient;
    private final ECDSASigner signer;
    private final NodeWatchdog watchdog;
    private final BridgeClient bridge;
    private final SequencerLogger heartbeat;
    private final Clock clock;

    private final int turnPeriodMs;
    private final int watcherPeriodMs;
    private final int releasePeriodMs;

    private ScheduledExecutorService executor;
    /** Set when the watchdog gives up, so that the caller can stop the process. */
    private volatile Runnable onNodeLost = () -> { };

    public SequencerScheduler(
        FederationWatcher watcher,
        List<BtcToRskClient> peginClients,
        BtcReleaseClient releaseClient,
        ECDSASigner signer,
        NodeWatchdog watchdog,
        BridgeClient bridge,
        SequencerLogger heartbeat,
        Clock clock,
        int turnPeriodMs,
        int watcherPeriodMs,
        int releasePeriodMs) {
        this.watcher = Objects.requireNonNull(watcher, "watcher");
        this.peginClients = List.copyOf(Objects.requireNonNull(peginClients, "peginClients"));
        this.releaseClient = Objects.requireNonNull(releaseClient, "releaseClient");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.turnPeriodMs = positive(turnPeriodMs, "turnPeriodMs");
        this.watcherPeriodMs = positive(watcherPeriodMs, "watcherPeriodMs");
        this.releasePeriodMs = positive(releasePeriodMs, "releasePeriodMs");
    }

    /** What to do when the node has been unreachable too long: normally, shut the process down. */
    public void onNodeLost(Runnable action) {
        this.onNodeLost = Objects.requireNonNull(action, "action");
    }

    public synchronized void start() {
        if (executor != null) {
            throw new IllegalStateException("Already started");
        }
        executor = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "sequencer");
            thread.setDaemon(false);
            return thread;
        });

        // Watching for a federation change comes first and without delay: everything else needs to
        // know which federation it is acting for.
        executor.scheduleAtFixedRate(
            guarded("watch", this::watch), 0, watcherPeriodMs, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(
            guarded("sign", this::sign), releasePeriodMs, releasePeriodMs, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(
            guarded("inform", this::inform), turnPeriodMs, turnPeriodMs, TimeUnit.MILLISECONDS);

        logger.info("[start] Turn every {}ms, signing every {}ms, watching every {}ms",
            turnPeriodMs, releasePeriodMs, watcherPeriodMs);
    }

    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        logger.info("[stop] Stopping");
        executor.shutdownNow();
        executor = null;
    }

    // ------------------------------------------------------------------ the three ticks

    // Package-private so a test can drive a tick without waiting on a timer.
    void watch() {
        if (!watchdog.recordAttempt(this::nodeAnswers)) {
            onNodeLost.run();
            return;
        }
        watcher.updateState();
        heartbeat.log();
    }

    void sign() {
        releaseClient.updateBridge();
    }

    /**
     * Informs the bridge, if it is this member's turn.
     *
     * <p>The turn is decided here rather than by waking on a schedule offset per member, because
     * the federation can change under us: the number of participants and this member's index both
     * move, and a fixed offset chosen at startup would be wrong from then on.
     */
    void inform() {
        if (!isOurTurn()) {
            return;
        }
        peginClients.forEach(BtcToRskClient::updateBridge);
    }

    /**
     * Whether to inform the bridge on this tick.
     *
     * <p>True in this member's own slot, and true always for a sequencer that is in no federation:
     * it has no slot to wait for, and what it may relay is open to anyone. The clients decide for
     * themselves which calls they are allowed to make.
     */
    boolean isOurTurn() {
        Optional<Integer> position = myPositionInTheFederation();
        if (position.isEmpty()) {
            logger.debug("[isOurTurn] No slot of our own; relaying whatever anyone may relay");
            return true;
        }

        int participants = watcher.getActiveFederation().map(Federation::getSize).orElse(1);
        boolean ours = new TurnScheduler(turnPeriodMs, participants).isTurnOf(clock.millis(), position.get());
        logger.debug("[isOurTurn] Slot {} of {}: {}", position.get(), participants, ours ? "ours" : "somebody else's");
        return ours;
    }

    /**
     * This member's index in the active federation, if it is in it.
     *
     * <p>By the BTC key, because that is the order the federation itself keeps and so the order
     * every member computes the same way.
     */
    private Optional<Integer> myPositionInTheFederation() {
        Optional<Federation> active = watcher.getActiveFederation();
        if (active.isEmpty()) {
            return Optional.empty();
        }
        try {
            BtcECKey btcPublicKey = signer.getPublicKey(SequencerKeyId.BTC.getKeyId()).toBtcKey();
            return active.get().getBtcPublicKeyIndex(btcPublicKey);
        } catch (SignerException e) {
            logger.error("[myPositionInTheFederation] Cannot read this sequencer's BTC public key: {}",
                e.getMessage(), e);
            return Optional.empty();
        }
    }

    private boolean nodeAnswers() {
        return bridge.nodeIsUsable();
    }

    /**
     * Keeps one tick's failure from stopping the timer that runs it.
     *
     * <p>A task that throws out of scheduleAtFixedRate is never run again, silently. Everything
     * here is periodic work that should be attempted afresh next time.
     */
    private Runnable guarded(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("[{}] Tick failed: {}", name, e.getMessage(), e);
            } catch (Error e) {
                logger.error("[{}] Tick failed unrecoverably", name, e);
                throw e;
            }
        };
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }
}
