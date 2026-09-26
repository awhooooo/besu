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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.Context;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.bitcoinj.script.ScriptChunk;
import co.rsk.bitcoinj.script.RedeemScriptParser;
import co.rsk.bitcoinj.script.RedeemScriptParserFactory;
import co.rsk.federate.BridgeEventReader;
import co.rsk.federate.FederatorSupport;
import co.rsk.federate.PegoutOutpointValues;
import co.rsk.federate.adapter.ThinConverter;
import co.rsk.federate.bitcoin.BitcoinWrapper;
import co.rsk.federate.rpc.EthClient;
import co.rsk.federate.signing.ECDSASigner;
import co.rsk.federate.signing.FederationCantSignException;
import co.rsk.federate.signing.FederatorAlreadySignedException;
import co.rsk.federate.signing.LegacySigHashCalculator;
import co.rsk.federate.signing.SegwitSigHashCalculator;
import co.rsk.federate.signing.SequencerKeyId;
import co.rsk.federate.signing.SigHashCalculator;
import co.rsk.federate.signing.SignerException;
import co.rsk.peg.BridgeEvents;
import co.rsk.peg.BridgeUtils;
import co.rsk.peg.StateForFederator;
import co.rsk.peg.bitcoin.BitcoinUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.federation.ErpFederation;
import co.rsk.peg.federation.Federation;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signs peg-outs and puts the finished ones on the bitcoin network.
 *
 * <p>Two halves that meet only at the bridge. On one side, every member signs every peg-out the
 * bridge is waiting on: a threshold needs all the signatures it can get, so unlike the peg-in side
 * there is no taking turns and nothing is redundant. On the other, once the bridge has collected
 * enough signatures it announces the finished transaction, and whoever sees the announcement sends
 * it to bitcoin.
 *
 * <p>The signing is where a mistake is expensive and quiet. A signature over the wrong digest is
 * still a valid signature; the bridge counts it, the threshold is met, and what bitcoin receives
 * is unspendable. So the digest is computed the way the input says it must be — segwit inputs
 * commit to the value being spent, legacy ones do not — and a peg-out whose values cannot be found
 * is left alone rather than signed with a guess.
 */
public class BtcReleaseClient {

    private static final Logger logger = LoggerFactory.getLogger(BtcReleaseClient.class);

    private final BitcoinWrapper bitcoinWrapper;
    private final FederatorSupport federatorSupport;
    private final BridgeConstants bridgeConstants;
    private final ECDSASigner signer;
    private final PegoutOutpointValues outpointValues;
    private final BridgeEventReader events;
    private final PegoutSignedCache signedCache;
    private final org.bitcoinj.core.NetworkParameters peerBtcParams;
    private final long releaseLookback;

    private final Set<Federation> observedFederations = new LinkedHashSet<>();
    /** Peg-outs already sent to bitcoin in this run; a repeat is harmless but pointless. */
    private final Set<Sha256Hash> broadcast = new HashSet<>();

    public BtcReleaseClient(
        BitcoinWrapper bitcoinWrapper,
        FederatorSupport federatorSupport,
        BridgeConstants bridgeConstants,
        ECDSASigner signer,
        PegoutOutpointValues outpointValues,
        BridgeEventReader events,
        PegoutSignedCache signedCache,
        long releaseLookback) {
        this.bitcoinWrapper = Objects.requireNonNull(bitcoinWrapper, "bitcoinWrapper");
        this.federatorSupport = Objects.requireNonNull(federatorSupport, "federatorSupport");
        this.bridgeConstants = Objects.requireNonNull(bridgeConstants, "bridgeConstants");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.outpointValues = Objects.requireNonNull(outpointValues, "outpointValues");
        this.events = Objects.requireNonNull(events, "events");
        this.signedCache = Objects.requireNonNull(signedCache, "signedCache");
        this.peerBtcParams = ThinConverter.toOriginal(bridgeConstants.getBtcParamsString());
        if (releaseLookback <= 0) {
            throw new IllegalArgumentException("A lookback must span at least one block");
        }
        this.releaseLookback = releaseLookback;
    }

    /**
     * Begins signing for a federation this federator belongs to.
     *
     * <p>Refuses one it does not belong to. Every signature would be made with a key the
     * federation's redeem script does not name, so the bridge would reject each of them, and the
     * only evidence would be transactions that cost gas and changed nothing. Better to fail at
     * the point the mistake was made.
     */
    public void start(Federation federation) {
        Objects.requireNonNull(federation, "federation");

        BtcECKey federatorPublicKey;
        try {
            federatorPublicKey = signer.getPublicKey(SequencerKeyId.BTC.getKeyId()).toBtcKey();
        } catch (SignerException e) {
            throw new IllegalStateException("Cannot read this federator's BTC public key", e);
        }
        if (!federation.hasBtcPublicKey(federatorPublicKey)) {
            throw new IllegalStateException(String.format(
                "This sequencer's BTC key %s is not one of federation %s's; it can sign nothing for it",
                federatorPublicKey, federation.getAddress()));
        }

        if (observedFederations.add(federation)) {
            logger.info("[start] Signing for federation {}", federation.getAddress());
        }
    }

    public void stop(Federation federation) {
        if (observedFederations.remove(federation)) {
            logger.info("[stop] No longer signing for federation {}", federation.getAddress());
        }
    }

    /** One pass: sign whatever is waiting, then send on whatever is finished. */
    public void updateBridge() {
        if (observedFederations.isEmpty()) {
            logger.warn("[updateBridge] Skipped: no federation");
            return;
        }
        if (!federatorSupport.nodeIsUsable()) {
            logger.warn("[updateBridge] Skipped: the node is still syncing");
            return;
        }

        try {
            signPegouts();
        } catch (Exception e) {
            logger.error("[updateBridge] Signing failed: {}", e.getMessage(), e);
        }

        try {
            broadcastFinishedPegouts();
        } catch (Exception e) {
            logger.error("[updateBridge] Broadcasting failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Signs every peg-out the bridge is waiting on that this federator can and has not.
     *
     * <p>Powpeg signed one per block, sorted newest first. Every signature counts towards the
     * threshold and a peg-out left unsigned is a peg-out nobody receives, so there is no reason to
     * hold any back; the bridge only promotes one peg-out per updateCollections in any case, so
     * the set is small.
     */
    void signPegouts() {
        Context.propagate(Context.getOrCreate(bridgeConstants.getBtcParams()));

        StateForFederator state = federatorSupport.getStateForBtcReleaseClient();
        Map<Hash, BtcTransaction> waiting = state.getRskTxsWaitingForSignatures();
        if (waiting.isEmpty()) {
            logger.debug("[signPegouts] Nothing waiting for signatures");
            return;
        }
        logger.info("[signPegouts] {} peg-outs waiting", waiting.size());

        long chainHeight = federatorSupport.getRskBestChainHeight();
        for (Map.Entry<Hash, BtcTransaction> entry : waiting.entrySet()) {
            try {
                signPegout(entry.getKey(), entry.getValue(), chainHeight);
            } catch (FederatorAlreadySignedException e) {
                logger.debug("[signPegouts] {}", e.getMessage());
            } catch (FederationCantSignException e) {
                logger.warn("[signPegouts] {}", e.getMessage());
            } catch (Exception e) {
                logger.error("[signPegouts] Peg-out created in {} failed: {}", entry.getKey(), e.getMessage(), e);
            }
        }
    }

    private void signPegout(Hash pegoutCreationRskTxHash, BtcTransaction pegoutBtcTx, long chainHeight)
        throws SignerException, FederatorAlreadySignedException, FederationCantSignException {

        if (signedCache.hasAlreadyBeenSigned(pegoutCreationRskTxHash)) {
            throw new FederatorAlreadySignedException(
                "Peg-out created in " + pegoutCreationRskTxHash + " was signed recently");
        }

        SigHashCalculator sigHashCalculator = sigHashCalculatorFor(pegoutBtcTx, chainHeight);
        validateCanBeSigned(pegoutBtcTx, sigHashCalculator);

        List<byte[]> signatures = new ArrayList<>(pegoutBtcTx.getInputs().size());
        for (int inputIndex = 0; inputIndex < pegoutBtcTx.getInputs().size(); inputIndex++) {
            Sha256Hash sigHash = sigHashCalculator.calculate(pegoutBtcTx, inputIndex);
            BtcECKey.ECDSASignature signature =
                signer.sign(SequencerKeyId.BTC.getKeyId(), Bytes32.wrap(sigHash.getBytes()));
            signatures.add(signature.encodeToDER());
        }

        BtcECKey federatorPublicKey = signer.getPublicKey(SequencerKeyId.BTC.getKeyId()).toBtcKey();
        federatorSupport.addSignature(federatorPublicKey, signatures, pegoutCreationRskTxHash);
        signedCache.put(pegoutCreationRskTxHash);
        logger.info("[signPegout] Signed {} inputs of the peg-out created in {}",
            signatures.size(), pegoutCreationRskTxHash);
    }

    /**
     * Picks the digest each of this peg-out's inputs must be signed over.
     *
     * <p>Per input rather than per transaction. A peg-out normally spends one federation's utxos
     * and so is all of one shape, but deciding from the first input would quietly sign the rest
     * the wrong way if that ever stopped holding, and the result would be signatures the bridge
     * accepts and bitcoin does not.
     *
     * <p>The values are looked up by the peg-out's own hash. A segwit transaction's txid does not
     * cover its witness, so it is the same hash the bridge announced against before any federator
     * had signed.
     */
    private SigHashCalculator sigHashCalculatorFor(BtcTransaction pegoutBtcTx, long chainHeight) {
        boolean anyInputIsSegwit = false;
        for (int inputIndex = 0; inputIndex < pegoutBtcTx.getInputs().size(); inputIndex++) {
            anyInputIsSegwit |= BitcoinUtils.inputHasWitness(pegoutBtcTx, inputIndex);
        }

        SigHashCalculator legacy = new LegacySigHashCalculator();
        if (!anyInputIsSegwit) {
            return legacy;
        }

        Sha256Hash announcedHash = pegoutBtcTx.getHash();
        List<Coin> values = outpointValues.valuesFor(announcedHash, chainHeight)
            .orElseThrow(() -> new IllegalStateException(String.format(
                "Peg-out %s has segwit inputs, whose signatures commit to what each input was worth, "
                    + "and the bridge's announcement of those values could not be found. Refusing to "
                    + "sign: a signature over a guessed value would be counted by the bridge and "
                    + "produce a transaction bitcoin will not accept.",
                announcedHash)));
        SigHashCalculator segwit = new SegwitSigHashCalculator(values);

        return (btcTx, inputIndex) -> BitcoinUtils.inputHasWitness(btcTx, inputIndex)
            ? segwit.calculate(btcTx, inputIndex)
            : legacy.calculate(btcTx, inputIndex);
    }

    /**
     * Refuses a peg-out this federator has already signed, or that no watched federation can spend.
     *
     * <p>The first is read off the transaction itself: a federator's signature is in the input's
     * script, so the peg-out says who has signed it. The second matters because a peg-out spending
     * a federation we are not part of cannot be signed by our key, and sending the signature anyway
     * would be a transaction the bridge rejects.
     */
    void validateCanBeSigned(BtcTransaction pegoutBtcTx, SigHashCalculator sigHashCalculator)
        throws SignerException, FederatorAlreadySignedException, FederationCantSignException {

        BtcECKey federatorPublicKey = signer.getPublicKey(SequencerKeyId.BTC.getKeyId()).toBtcKey();

        for (int inputIndex = 0; inputIndex < pegoutBtcTx.getInputs().size(); inputIndex++) {
            final int index = inputIndex;
            Script redeemScript = BitcoinUtils.extractRedeemScriptFromInput(pegoutBtcTx, inputIndex)
                .orElseThrow(() -> new IllegalStateException(String.format(
                    "No redeem script in input %d of peg-out %s", index, pegoutBtcTx.getHash())));

            Sha256Hash sigHash = sigHashCalculator.calculate(pegoutBtcTx, inputIndex);
            if (BridgeUtils.isInputSignedByThisFederator(pegoutBtcTx, inputIndex, federatorPublicKey, sigHash)) {
                throw new FederatorAlreadySignedException(String.format(
                    "Input %d of peg-out %s is already signed by %s",
                    inputIndex, pegoutBtcTx.getHash(), federatorPublicKey));
            }

            Script standardRedeemScript = standardRedeemScriptOf(redeemScript);
            boolean anyWatchedFederationSpends = observedFederations.stream()
                .anyMatch(federation -> defaultRedeemScriptOf(federation).equals(standardRedeemScript));
            if (!anyWatchedFederationSpends) {
                throw new FederationCantSignException(String.format(
                    "Input %d of peg-out %s spends a federation this sequencer does not watch",
                    inputIndex, pegoutBtcTx.getHash()));
            }
        }
    }

    /**
     * Sends peg-outs the bridge has finished collecting signatures for.
     *
     * <p>The bridge announces each one, so this is a matter of reading recent announcements rather
     * than watching for a threshold to be crossed. Announcements are re-read each pass and each
     * peg-out is sent once per run; sending one twice would be harmless, but a restart re-sending
     * everything recent is a useful accident rather than a wasteful one, since a broadcast that
     * failed to propagate gets another chance.
     */
    void broadcastFinishedPegouts() {
        long chainHeight = federatorSupport.getRskBestChainHeight();
        // Every announcement in the window, not just the newest: several peg-outs can finish close
        // together, and stopping at the first one found would leave the others unsent. The window
        // is how far back a restart looks, so it is about how long this process might have been
        // down, not about anything the bridge counts.
        long from = Math.max(0, chainHeight - releaseLookback + 1);
        List<EthClient.LogEntry> announcements = events.findAll(
            from,
            chainHeight,
            List.of(List.of(BridgeEventReader.topicOf(BridgeEvents.RELEASE_BTC))));

        for (EthClient.LogEntry announcement : announcements) {
            byte[] raw = (byte[]) BridgeEvents.RELEASE_BTC.getEvent().decodeEventData(announcement.data())[0];
            BtcTransaction signedPegout = new BtcTransaction(bridgeConstants.getBtcParams(), raw);
            if (!broadcast.add(signedPegout.getHash())) {
                continue;
            }
            logger.info("[broadcastFinishedPegouts] Peg-out {} is fully signed; sending it to bitcoin",
                signedPegout.getHash());
            bitcoinWrapper.broadcast(ThinConverter.toOriginal(peerBtcParams, signedPegout));
        }
    }

    private static Script standardRedeemScriptOf(Script redeemScript) {
        RedeemScriptParser parser = RedeemScriptParserFactory.get(redeemScript.getChunks());
        List<ScriptChunk> chunks = parser.extractStandardRedeemScriptChunks();
        return new ScriptBuilder().addChunks(chunks).build();
    }

    private static Script defaultRedeemScriptOf(Federation federation) {
        return federation instanceof ErpFederation erp ? erp.getDefaultRedeemScript() : federation.getRedeemScript();
    }

    /** For tests and logging. */
    Optional<Federation> watched(Federation federation) {
        return observedFederations.contains(federation) ? Optional.of(federation) : Optional.empty();
    }
}
