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
import co.rsk.peg.StateForProposedFederator;
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
     * Begins watching a federation.
     *
     * <p>Accepts one whose keys this sequencer does not hold, and says so. Holding none of them is
     * an ordinary state rather than a misconfiguration: federation keys are not reused across a
     * change, so a member of a proposed federation belongs to nothing live until the change
     * completes, and that is exactly when it has to be running in order to sign the validation
     * spend. Whether a signature is worth making is decided per peg-out instead, where the
     * redeem script of the input says which federation is being spent.
     */
    public void start(Federation federation) {
        Objects.requireNonNull(federation, "federation");
        if (!observedFederations.add(federation)) {
            return;
        }
        if (isMemberOf(federation)) {
            logger.info("[start] Signing for federation {}", federation.getAddress());
        } else {
            logger.info(
                "[start] Watching federation {}, but this sequencer holds none of its keys and will "
                    + "sign nothing for it",
                federation.getAddress());
        }
    }

    /**
     * Whether a signature from this sequencer would count towards this federation's threshold.
     *
     * <p>Both keys have to belong to it, because the bridge checks both and for different things.
     * The BTC key decides whose signature it is: addSignature looks the member up by it. The RSK
     * key decides whether the call is allowed at all, by comparing the sending address against
     * each member's. A sequencer configured with one federation's BTC key and another's RSK key
     * would sign correctly and have the transaction rejected before anyone looked at the
     * signature.
     */
    private boolean isMemberOf(Federation federation) {
        try {
            BtcECKey btcPublicKey = signer.getPublicKey(SequencerKeyId.BTC.getKeyId()).toBtcKey();
            if (!federation.hasBtcPublicKey(btcPublicKey)) {
                return false;
            }
        } catch (SignerException e) {
            logger.error("[isMemberOf] Cannot read this sequencer's BTC public key: {}", e.getMessage(), e);
            return false;
        }

        byte[] senderAddress = federatorSupport.senderAddress().getBytes().toArrayUnsafe();
        if (!federation.hasMemberWithRskAddress(senderAddress)) {
            logger.warn(
                "[isMemberOf] This sequencer holds a BTC key of federation {} but sends from {}, which is "
                    + "not one of its members' addresses. The bridge would refuse the call before reading "
                    + "the signature.",
                federation.getAddress(), federatorSupport.senderAddress());
            return false;
        }
        return true;
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

        // Before the peg-outs, because a proposed federation is waiting on this to become the
        // federation at all, and because there is at most one of them.
        try {
            signSvpSpendTransaction();
        } catch (Exception e) {
            logger.error("[updateBridge] Signing the validation spend failed: {}", e.getMessage(), e);
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

    /**
     * Signs the transaction that proves a proposed federation can spend.
     *
     * <p>Before a federation is handed the peg, the bridge sends it a small amount and builds a
     * transaction spending that back, which only the proposed federation's members can sign. If
     * they cannot, the change is abandoned rather than discovered later with the whole peg behind
     * it.
     *
     * <p>Signed exactly like a peg-out, because it is one; it is only kept in a different place
     * because a different federation signs it. This sequencer will be able to when it holds a
     * proposed federation's keys, which is the state a new member is in before a change completes,
     * and will not when it holds the outgoing federation's — those members have already done their
     * part by signing the transaction that funded the proposal.
     */
    void signSvpSpendTransaction() {
        Context.propagate(Context.getOrCreate(bridgeConstants.getBtcParams()));

        Map.Entry<Hash, BtcTransaction> waiting = federatorSupport.getStateForProposedFederator()
            .map(StateForProposedFederator::getSvpSpendTxWaitingForSignatures)
            .orElse(null);
        if (waiting == null) {
            return;
        }

        Hash creationRskTxHash = waiting.getKey();
        BtcTransaction svpSpendTx = waiting.getValue();
        long chainHeight = federatorSupport.getRskBestChainHeight();

        if (!isReadyToSign(svpSpendTx, chainHeight)) {
            return;
        }

        try {
            signPegout(creationRskTxHash, svpSpendTx, chainHeight);
        } catch (FederatorAlreadySignedException e) {
            logger.debug("[signSvpSpendTransaction] {}", e.getMessage());
        } catch (FederationCantSignException e) {
            // The ordinary case for a member of the outgoing federation: the proposal is not
            // theirs to prove.
            logger.debug("[signSvpSpendTransaction] {}", e.getMessage());
        } catch (Exception e) {
            logger.error("[signSvpSpendTransaction] Validation spend created in {} failed: {}",
                creationRskTxHash, e.getMessage(), e);
        }
    }

    /**
     * Whether the validation spend has waited as long as any other peg-out must.
     *
     * <p>It is held back for the same reason: once signed it goes to bitcoin and cannot be
     * recalled. The block it was built in comes from the announcement that also carries its
     * outpoint values, so finding out costs nothing beyond the query already needed to sign it.
     */
    private boolean isReadyToSign(BtcTransaction svpSpendTx, long chainHeight) {
        long required = bridgeConstants.getRsk2BtcMinimumAcceptableConfirmations();
        Optional<PegoutOutpointValues.Announcement> announcement =
            outpointValues.announcementFor(svpSpendTx.getHash(), chainHeight);

        if (announcement.isEmpty()) {
            logger.warn("[isReadyToSign] No announcement found for validation spend {}; cannot tell "
                + "how long it has waited, and will not sign it", svpSpendTx.getHash());
            return false;
        }

        long waited = chainHeight - announcement.get().blockNumber();
        if (waited < required) {
            logger.debug("[isReadyToSign] Validation spend {} has waited {} of {} blocks",
                svpSpendTx.getHash(), waited, required);
            return false;
        }
        return true;
    }

    private void signPegout(Hash pegoutCreationRskTxHash, BtcTransaction pegoutBtcTx, long chainHeight)
        throws SignerException, FederatorAlreadySignedException, FederationCantSignException {

        if (signedCache.hasAlreadyBeenSigned(pegoutCreationRskTxHash)) {
            throw new FederatorAlreadySignedException(
                "Peg-out created in " + pegoutCreationRskTxHash + " was signed recently");
        }

        SigHashCalculator sigHashCalculator = sigHashCalculatorFor(pegoutBtcTx, chainHeight);
        validateCanBeSigned(pegoutBtcTx, sigHashCalculator);

        BtcECKey federatorPublicKey = signer.getPublicKey(SequencerKeyId.BTC.getKeyId()).toBtcKey();
        List<byte[]> signatures = new ArrayList<>(pegoutBtcTx.getInputs().size());
        for (int inputIndex = 0; inputIndex < pegoutBtcTx.getInputs().size(); inputIndex++) {
            Sha256Hash sigHash = sigHashCalculator.calculate(pegoutBtcTx, inputIndex);
            signatures.add(signDigest(federatorPublicKey, sigHash).encodeToDER());
        }

        federatorSupport.addSignature(federatorPublicKey, signatures, pegoutCreationRskTxHash);
        signedCache.put(pegoutCreationRskTxHash);
        logger.info("[signPegout] Signed {} inputs of the peg-out created in {}",
            signatures.size(), pegoutCreationRskTxHash);
    }

    /**
     * Signs one input's digest, in the form the bridge will accept.
     *
     * <p>Two steps that a key file makes look unnecessary. The signature is canonicalised because
     * the bridge refuses a high-S one outright; bitcoinj canonicalises inside its own signing, so
     * leaving it out here would make this correct only for as long as the key stays in a file, and
     * a device returns whichever S it computed.
     *
     * <p>It is then verified against the digest and this sequencer's own public key, because the
     * bridge verifies it too and answers a bad one by logging and returning. Nothing comes back from
     * addSignature to say so, so an unchecked signature would be dropped there while this side
     * recorded the peg-out as signed and went quiet about it until the cache expired.
     */
    private BtcECKey.ECDSASignature signDigest(BtcECKey federatorPublicKey, Sha256Hash sigHash)
        throws SignerException {

        BtcECKey.ECDSASignature signature = signer
            .sign(SequencerKeyId.BTC.getKeyId(), Bytes32.wrap(sigHash.getBytes()))
            .toCanonicalised();

        if (!federatorPublicKey.verify(sigHash, signature)) {
            throw new SignerException(String.format(
                "%s returned a signature over digest %s that does not verify against its own public"
                    + " key %s. The digest signed was not the one asked for, or the key is not the"
                    + " one it claims.",
                SequencerKeyId.BTC.getKeyId(), sigHash, federatorPublicKey));
        }
        return signature;
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

            // A federation whose keys this sequencer does not hold is no use here: the signature
            // would be made with a key the redeem script does not name, the bridge would reject
            // it, and the only trace would be gas spent once a turn for as long as the peg-out
            // was waiting.
            Script standardRedeemScript = standardRedeemScriptOf(redeemScript);
            boolean spendsAFederationWeCanSignFor = observedFederations.stream()
                .filter(this::isMemberOf)
                .anyMatch(federation -> defaultRedeemScriptOf(federation).equals(standardRedeemScript));
            if (!spendsAFederationWeCanSignFor) {
                boolean watchedAtAll = observedFederations.stream()
                    .anyMatch(federation -> defaultRedeemScriptOf(federation).equals(standardRedeemScript));
                throw new FederationCantSignException(String.format(
                    "Input %d of peg-out %s spends a federation this sequencer %s",
                    inputIndex, pegoutBtcTx.getHash(),
                    watchedAtAll ? "watches but holds no key of" : "does not watch"));
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
