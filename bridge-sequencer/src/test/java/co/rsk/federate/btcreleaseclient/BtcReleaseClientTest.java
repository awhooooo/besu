package co.rsk.federate.btcreleaseclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.federate.BridgeClient;
import co.rsk.federate.BridgeEventReader;
import co.rsk.federate.FederatorSupport;
import co.rsk.federate.GasPolicy;
import co.rsk.federate.PegoutOutpointValues;
import co.rsk.federate.signing.ECDSASigner;
import co.rsk.federate.signing.ECDSASignerFromFileKey;
import co.rsk.federate.signing.ECPublicKey;
import co.rsk.federate.signing.KeyId;
import co.rsk.federate.signing.SequencerKeyId;
import co.rsk.federate.signing.SignerException;
import co.rsk.federate.testing.FakeBitcoinWrapper;
import co.rsk.federate.testing.FakeNode;
import co.rsk.federate.testing.PegoutFixture;
import co.rsk.federate.tx.LegacyTransactionSigner;
import co.rsk.peg.BridgeEvents;
import co.rsk.peg.BridgeMethods;
import co.rsk.peg.StateForFederator;
import co.rsk.peg.bitcoin.BitcoinUtils;
import co.rsk.peg.bitcoin.UtxoUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.federation.Federation;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The signing half of the peg, driven through the real signer and the real ABI.
 *
 * <p>A signature over the wrong digest is not an error anywhere: it is well-formed, the bridge
 * counts it, and only bitcoin refuses the result. So the assertions here are about the digest the
 * signature is over and about what is refused, not about whether a call was made.
 */
class BtcReleaseClientTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
    private static final String RSK_KEY = "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";
    private static final Coin PER_INPUT = Coin.COIN.multiply(3);
    private static final long CHAIN_HEIGHT = 100_000;

    @TempDir Path home;

    private BridgeConstants bridgeConstants;
    private NetworkParameters btcParams;
    private List<BtcECKey> keys;
    private FakeNode node;
    private FakeBitcoinWrapper bitcoin;
    private FederatorSupport federatorSupport;
    private ECDSASigner signer;
    private BridgeEventReader events;

    @BeforeEach
    void setUp() throws Exception {
        bridgeConstants = new BridgeRegTestConstants();
        btcParams = bridgeConstants.getBtcParams();
        keys = PegoutFixture.federationKeys(3);

        node = new FakeNode().atHeight(CHAIN_HEIGHT);
        bitcoin = new FakeBitcoinWrapper();
        federatorSupport = new FederatorSupport(bridgeClient(), btcParams);
        signer = federatorSigner();
        events = new BridgeEventReader(node, 4_500);
    }

    // ---------------------------------------------------------------- signing

    @Test
    void aLegacyPegoutIsSignedOverTheDigestTheBridgeWillCheck() throws Exception {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 2, PER_INPUT);
        Hash creation = Hash.fromHexStringLenient("0xc1");
        bridgeIsWaitingOn(creation, pegout);

        client(federation).signPegouts();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        Object[] signatures = (Object[]) sent.arguments()[1];
        assertThat(signatures).hasSize(2);
        for (int input = 0; input < 2; input++) {
            Sha256Hash expected = BitcoinUtils.generateSigHashForLegacyTransactionInput(pegout, input);
            assertThat(federatorKey().verify(expected, decode((byte[]) signatures[input]))).isTrue();
        }
    }

    @Test
    void aSegwitPegoutIsSignedOverTheValuesTheBridgeAnnounced() throws Exception {
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction pegout = PegoutFixture.segwitPegout(federation, btcParams, 2, PER_INPUT);
        Hash creation = Hash.fromHexStringLenient("0xc2");
        bridgeIsWaitingOn(creation, pegout);
        bridgeAnnouncedValues(pegout, PegoutFixture.outpointValues(2, PER_INPUT), CHAIN_HEIGHT - 3_600);

        client(federation).signPegouts();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        Object[] signatures = (Object[]) sent.arguments()[1];
        for (int input = 0; input < 2; input++) {
            Sha256Hash expected =
                BitcoinUtils.generateSigHashForSegwitTransactionInput(pegout, input, PER_INPUT);
            assertThat(federatorKey().verify(expected, decode((byte[]) signatures[input]))).isTrue();
        }
    }

    @Test
    void aSegwitPegoutWhoseValuesCannotBeFoundIsNotSigned() throws Exception {
        // Signing it would mean guessing what each input was worth. The signature would be valid
        // arithmetic over a message no verifier reconstructs, the bridge would count it, and the
        // transaction bitcoin received would be unspendable.
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction pegout = PegoutFixture.segwitPegout(federation, btcParams, 2, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc3"), pegout);
        // Deliberately no announcement.

        client(federation).signPegouts();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void thePublicKeySentIsTheOneTheSignaturesWereMadeWith() throws Exception {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc4"), pegout);

        client(federation).signPegouts();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        assertThat((byte[]) sent.arguments()[0]).isEqualTo(federatorKey().getPubKey());
    }

    @Test
    void theSignaturesAreSentAgainstTheTransactionThatCreatedThePegout() throws Exception {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        Hash creation = Hash.fromHexStringLenient("0xabcdef");
        bridgeIsWaitingOn(creation, pegout);

        client(federation).signPegouts();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        assertThat(Bytes.wrap((byte[]) sent.arguments()[2])).isEqualTo(creation.getBytes());
    }

    @Test
    void everyWaitingPegoutIsSignedRatherThanOnePerTurn() throws Exception {
        // Every signature counts towards the threshold, and one left unsigned is a peg-out
        // somebody never receives.
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        TreeMap<Hash, BtcTransaction> waiting = new TreeMap<>();
        for (int i = 1; i <= 4; i++) {
            waiting.put(Hash.fromHexStringLenient("0xd" + i),
                PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT.add(Coin.valueOf(i))));
        }
        bridgeIsWaitingOn(waiting);

        client(federation).signPegouts();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).hasSize(4);
    }

    @Test
    void aPegoutJustSignedIsNotSignedAgainOnTheNextTurn() throws Exception {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc5"), pegout);
        BtcReleaseClient client = client(federation);

        client.signPegouts();
        client.signPegouts();

        // The bridge still reports it as waiting until the signature is included.
        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).hasSize(1);
    }

    @Test
    void aPegoutSpendingAFederationWeDoNotWatchIsNotSigned() throws Exception {
        Federation ours = PegoutFixture.standardFederation(keys, btcParams);
        Federation theirs = PegoutFixture.standardFederation(PegoutFixture.strangersKeys(3), btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(theirs, btcParams, 1, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc6"), pegout);

        client(ours).signPegouts();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void nothingIsSentWhenNothingIsWaiting() throws Exception {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        bridgeIsWaitingOn(new TreeMap<>());

        client(federation).signPegouts();

        assertThat(node.sent()).isEmpty();
    }

    @Test
    void aSyncingNodeIsLeftAlone() throws Exception {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc7"),
            PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT));
        node.syncing(true);

        client(federation).updateBridge();

        assertThat(node.sent()).isEmpty();
    }

    @Test
    void aClientWithNoFederationDoesNothing() throws Exception {
        BtcReleaseClient client = newClient();

        client.updateBridge();

        assertThat(node.sent()).isEmpty();
    }

    @Test
    void oneBadPegoutDoesNotStopTheOthers() throws Exception {
        Federation ours = PegoutFixture.standardFederation(keys, btcParams);
        Federation theirs = PegoutFixture.standardFederation(PegoutFixture.strangersKeys(3), btcParams);
        TreeMap<Hash, BtcTransaction> waiting = new TreeMap<>();
        waiting.put(Hash.fromHexStringLenient("0xe1"), PegoutFixture.legacyPegout(theirs, btcParams, 1, PER_INPUT));
        waiting.put(Hash.fromHexStringLenient("0xe2"), PegoutFixture.legacyPegout(ours, btcParams, 1, PER_INPUT));
        bridgeIsWaitingOn(waiting);

        client(ours).signPegouts();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).hasSize(1);
    }

    @Test
    void aPegoutAnotherFederatorHasAlreadySignedIsStillSignedCorrectly() throws Exception {
        // The real condition: by the time this federator sees a peg-out, others have signed it.
        // For a segwit peg-out their signatures sit in the witness, which the txid does not cover,
        // so the bridge's announcement is still found under the same hash.
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction pegout = PegoutFixture.segwitPegout(federation, btcParams, 1, PER_INPUT);
        bridgeAnnouncedValues(pegout, PegoutFixture.outpointValues(1, PER_INPUT), CHAIN_HEIGHT - 3_600);

        Sha256Hash beforeAnyoneSigned = pegout.getHash();
        putSomebodyElsesSignatureInTheWitness(pegout);
        assertThat(pegout.getHash()).isEqualTo(beforeAnyoneSigned);

        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc9"), pegout);
        client(federation).signPegouts();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        Object[] signatures = (Object[]) sent.arguments()[1];
        Sha256Hash expected = BitcoinUtils.generateSigHashForSegwitTransactionInput(pegout, 0, PER_INPUT);
        assertThat(federatorKey().verify(expected, decode((byte[]) signatures[0]))).isTrue();
    }

    @Test
    void eachInputIsSignedTheWayThatInputRequires() throws Exception {
        // A peg-out normally spends one federation and so is all one shape. Deciding from the
        // first input alone would sign the rest the wrong way if that ever stopped holding, and
        // the bridge would accept every one of those signatures.
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction pegout = PegoutFixture.segwitPegout(federation, btcParams, 2, PER_INPUT);
        // Make the second input legacy: no witness, redeem script in the scriptSig.
        pegout.setWitness(1, new co.rsk.bitcoinj.core.TransactionWitness(0));
        pegout.getInput(1).setScriptSig(
            PegoutFixture.legacyPegout(PegoutFixture.standardFederation(keys, btcParams), btcParams, 1, PER_INPUT)
                .getInput(0).getScriptSig());
        bridgeAnnouncedValues(pegout, PegoutFixture.outpointValues(2, PER_INPUT), CHAIN_HEIGHT - 3_600);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xca"), pegout);

        client(federation).signPegouts();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        Object[] signatures = (Object[]) sent.arguments()[1];
        assertThat(BitcoinUtils.inputHasWitness(pegout, 0)).isTrue();
        assertThat(BitcoinUtils.inputHasWitness(pegout, 1)).isFalse();
        assertThat(federatorKey().verify(
            BitcoinUtils.generateSigHashForSegwitTransactionInput(pegout, 0, PER_INPUT),
            decode((byte[]) signatures[0]))).isTrue();
        assertThat(federatorKey().verify(
            BitcoinUtils.generateSigHashForLegacyTransactionInput(pegout, 1),
            decode((byte[]) signatures[1]))).isTrue();
    }

    @Test
    void aFederationWhoseKeysThisSequencerDoesNotHoldIsWatchedButNotSignedFor() throws Exception {
        // Keys are not reused across a federation change, so a member of a proposed federation
        // belongs to nothing live until the change completes. The process has to run anyway. What
        // it must not do is sign: the bridge would reject a key its redeem script does not name,
        // once a turn, for as long as the peg-out waited.
        Federation somebodyElses = PegoutFixture.standardFederation(PegoutFixture.strangersKeys(3), btcParams);
        BtcTransaction theirPegout = PegoutFixture.legacyPegout(somebodyElses, btcParams, 1, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xcc"), theirPegout);

        BtcReleaseClient client = newClient();
        client.start(somebodyElses);
        client.signPegouts();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void holdingOneFederationsKeysDoesNotMeanSigningForAnother() throws Exception {
        // Both watched, only one of them ours. The redeem script of the input decides.
        Federation ours = PegoutFixture.standardFederation(keys, btcParams);
        Federation theirs = PegoutFixture.standardFederation(PegoutFixture.strangersKeys(3), btcParams);
        TreeMap<Hash, BtcTransaction> waiting = new TreeMap<>();
        waiting.put(Hash.fromHexStringLenient("0xf1"), PegoutFixture.legacyPegout(theirs, btcParams, 1, PER_INPUT));
        waiting.put(Hash.fromHexStringLenient("0xf2"), PegoutFixture.legacyPegout(ours, btcParams, 1, PER_INPUT));
        bridgeIsWaitingOn(waiting);

        BtcReleaseClient client = newClient();
        client.start(ours);
        client.start(theirs);
        client.signPegouts();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).hasSize(1);
        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        assertThat(Bytes.wrap((byte[]) sent.arguments()[2])).isEqualTo(Hash.fromHexStringLenient("0xf2").getBytes());
    }

    @Test
    void anInputThisFederatorAlreadySignedIsNotSignedAgainEvenWithAColdCache() throws Exception {
        // The cache lapses after half an hour and is empty after a restart, so this is the guard
        // that actually holds: the federator's own signature is already in the transaction, and
        // the transaction says so.
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        putOurOwnSignatureInTheScriptSig(pegout);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xcb"), pegout);

        // A brand new client, so nothing is remembered from a previous turn.
        client(federation).signPegouts();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void holdingTheBtcKeyButNotTheRskKeyIsNotMembership() throws Exception {
        // What mismatched key files produce. The signature would be correct and the transaction
        // carrying it refused before anyone read it, because the bridge checks the sender's
        // address against the federation as well as the signing key.
        Federation mismatched = PegoutFixture.federationWithOurBtcKeyButNotOurRskKey(btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(mismatched, btcParams, 1, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0x7a"), pegout);

        BtcReleaseClient client = newClient();
        client.start(mismatched);
        client.signPegouts();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void aHighSSignatureIsMadeCanonicalBeforeItIsSent() throws Exception {
        // A key file cannot produce one, because bitcoinj canonicalises inside its own signing. A
        // device returns whichever S it computed, and the bridge refuses a high-S signature by
        // logging and returning, so one left alone would be dropped there in silence.
        signer = signerReturning(signature ->
            new BtcECKey.ECDSASignature(signature.r, BtcECKey.CURVE.getN().subtract(signature.s)));

        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc5"), pegout);

        client(federation).signPegouts();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        BtcECKey.ECDSASignature signature = decode((byte[]) ((Object[]) sent.arguments()[1])[0]);
        assertThat(signature.s).isLessThanOrEqualTo(BtcECKey.HALF_CURVE_ORDER);
        assertThat(federatorKey().verify(
            BitcoinUtils.generateSigHashForLegacyTransactionInput(pegout, 0), signature)).isTrue();
    }

    @Test
    void aSignatureOverTheWrongDigestIsNotSentAndDoesNotCountAsHavingSigned() throws Exception {
        // The failure a signer this process cannot see inside makes possible: a well-formed
        // signature by the right key over something else. Well-formed is all the bridge's caller
        // can observe, since addSignature answers nothing, so it has to be caught here.
        AtomicBoolean misbehaving = new AtomicBoolean(true);
        signer = signerReturning(signature ->
            misbehaving.getAndSet(false) ? keys.get(0).sign(Sha256Hash.ZERO_HASH) : signature);

        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc6"), pegout);
        BtcReleaseClient client = client(federation);

        client.signPegouts();
        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();

        // Nothing was sent, so nothing was signed, and the next pass has to try again rather than
        // find the peg-out in the cache and skip it forever.
        client.signPegouts();
        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        assertThat(federatorKey().verify(
            BitcoinUtils.generateSigHashForLegacyTransactionInput(pegout, 0),
            decode((byte[]) ((Object[]) sent.arguments()[1])[0]))).isTrue();
    }

    // ---------------------------------------------------------------- the validation spend

    @Test
    void theValidationSpendIsSignedByTheProposedFederationsMembers() throws Exception {
        // The half of the ceremony the incoming members do. They hold none of the active
        // federation's keys, and this is the only thing they can sign.
        Federation proposed = proposedFederation();
        BtcTransaction svpSpend = PegoutFixture.segwitPegout(proposed, btcParams, 1, PER_INPUT);
        svpIsWaitingOn(Hash.fromHexStringLenient("0x5b"), svpSpend);
        bridgeAnnouncedValues(svpSpend, PegoutFixture.outpointValues(1, PER_INPUT), CHAIN_HEIGHT - 3_600);
        bridgeIsWaitingOn(new TreeMap<>());

        BtcReleaseClient client = newClient();
        client.start(proposed);
        client.signSvpSpendTransaction();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.ADD_SIGNATURE).orElseThrow();
        Object[] signatures = (Object[]) sent.arguments()[1];
        Sha256Hash expected = BitcoinUtils.generateSigHashForSegwitTransactionInput(svpSpend, 0, PER_INPUT);
        assertThat(federatorKey().verify(expected, decode((byte[]) signatures[0]))).isTrue();
        assertThat((byte[]) sent.arguments()[0]).isEqualTo(federatorKey().getPubKey());
    }

    @Test
    void theValidationSpendIsNotSignedByTheOutgoingFederationsMembers() throws Exception {
        // Their part was funding it. Keys are not reused across a change, so the proposal is
        // built from keys this sequencer does not hold, and every signature it made would be one
        // the bridge rejects.
        Federation proposed = proposedFederationOfStrangers();
        Federation ours = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction svpSpend = PegoutFixture.segwitPegout(proposed, btcParams, 1, PER_INPUT);
        svpIsWaitingOn(Hash.fromHexStringLenient("0x5c"), svpSpend);
        bridgeAnnouncedValues(svpSpend, PegoutFixture.outpointValues(1, PER_INPUT), CHAIN_HEIGHT - 3_600);

        BtcReleaseClient client = newClient();
        client.start(ours);
        client.start(proposed);
        client.signSvpSpendTransaction();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void theValidationSpendWaitsAsLongAsAnyOtherPegout() throws Exception {
        // Once signed it goes to bitcoin and cannot be recalled, which is the same reason every
        // peg-out waits.
        Federation proposed = proposedFederation();
        BtcTransaction svpSpend = PegoutFixture.segwitPegout(proposed, btcParams, 1, PER_INPUT);
        svpIsWaitingOn(Hash.fromHexStringLenient("0x5d"), svpSpend);
        // Built two blocks ago; regtest wants three.
        bridgeAnnouncedValues(svpSpend, PegoutFixture.outpointValues(1, PER_INPUT), CHAIN_HEIGHT - 2);

        BtcReleaseClient client = newClient();
        client.start(proposed);
        client.signSvpSpendTransaction();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void theValidationSpendIsSignedOnceItHasWaited() throws Exception {
        Federation proposed = proposedFederation();
        BtcTransaction svpSpend = PegoutFixture.segwitPegout(proposed, btcParams, 1, PER_INPUT);
        svpIsWaitingOn(Hash.fromHexStringLenient("0x5e"), svpSpend);
        bridgeAnnouncedValues(svpSpend, PegoutFixture.outpointValues(1, PER_INPUT), CHAIN_HEIGHT - 3);

        BtcReleaseClient client = newClient();
        client.start(proposed);
        client.signSvpSpendTransaction();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).hasSize(1);
    }

    @Test
    void aValidationSpendWithNoAnnouncementIsLeftAlone() throws Exception {
        // Without it there is no telling how long it has waited, nor what its inputs were worth.
        Federation proposed = proposedFederation();
        BtcTransaction svpSpend = PegoutFixture.segwitPegout(proposed, btcParams, 1, PER_INPUT);
        svpIsWaitingOn(Hash.fromHexStringLenient("0x5f"), svpSpend);

        BtcReleaseClient client = newClient();
        client.start(proposed);
        client.signSvpSpendTransaction();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void thereIsUsuallyNoValidationSpendAtAll() throws Exception {
        Federation ours = PegoutFixture.standardFederation(keys, btcParams);
        node.answering(BridgeMethods.GET_STATE_FOR_SVP_CLIENT, new byte[0]);

        BtcReleaseClient client = newClient();
        client.start(ours);
        client.signSvpSpendTransaction();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
    }

    @Test
    void theValidationSpendIsSignedBeforeThePegouts() throws Exception {
        // A proposed federation is waiting on it to become a federation at all.
        Federation proposed = proposedFederation();
        Federation ours = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction svpSpend = PegoutFixture.segwitPegout(proposed, btcParams, 1, PER_INPUT);
        svpIsWaitingOn(Hash.fromHexStringLenient("0x60"), svpSpend);
        bridgeAnnouncedValues(svpSpend, PegoutFixture.outpointValues(1, PER_INPUT), CHAIN_HEIGHT - 3_600);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0x61"),
            PegoutFixture.legacyPegout(ours, btcParams, 1, PER_INPUT));

        BtcReleaseClient client = newClient();
        client.start(ours);
        client.start(proposed);
        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).hasSize(2);
        assertThat(Bytes.wrap((byte[]) node.sentOf(BridgeMethods.ADD_SIGNATURE).get(0).arguments()[2]))
            .isEqualTo(Hash.fromHexStringLenient("0x60").getBytes());
    }

    // ---------------------------------------------------------------- broadcasting

    @Test
    void aFinishedPegoutIsSentToBitcoin() throws Exception {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction finished = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        bridgeAnnouncedRelease(finished, CHAIN_HEIGHT - 10);
        bridgeIsWaitingOn(new TreeMap<>());

        client(federation).updateBridge();

        assertThat(bitcoin.broadcast()).hasSize(1);
        assertThat(bitcoin.broadcast().get(0).getTxId().toString()).isEqualTo(finished.getHash().toString());
    }

    @Test
    void aPegoutIsNotSentTwiceInOneRun() throws Exception {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        bridgeAnnouncedRelease(PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT), CHAIN_HEIGHT - 10);
        bridgeIsWaitingOn(new TreeMap<>());
        BtcReleaseClient client = client(federation);

        client.updateBridge();
        client.updateBridge();

        assertThat(bitcoin.broadcast()).hasSize(1);
    }

    @Test
    void aSegwitPegoutKeepsItsWitnessOnTheWayToBitcoin() throws Exception {
        // The signatures of a segwit peg-out are in the witness. A conversion that dropped it
        // would broadcast a transaction that looks complete and spends nothing.
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction finished = PegoutFixture.segwitPegout(federation, btcParams, 1, PER_INPUT);
        bridgeAnnouncedRelease(finished, CHAIN_HEIGHT - 10);
        bridgeIsWaitingOn(new TreeMap<>());

        client(federation).updateBridge();

        org.bitcoinj.core.Transaction broadcast = bitcoin.broadcast().get(0);
        assertThat(finished.hasWitness()).isTrue();
        assertThat(broadcast.hasWitnesses()).isTrue();
        assertThat(broadcast.bitcoinSerialize()).isEqualTo(finished.bitcoinSerialize());
    }

    @Test
    void signingFailureDoesNotStopBroadcasting() throws Exception {
        // The two halves are independent: coins already on their way should not wait on a peg-out
        // that cannot be signed.
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction unsignable = PegoutFixture.segwitPegout(federation, btcParams, 1, PER_INPUT);
        bridgeIsWaitingOn(Hash.fromHexStringLenient("0xc8"), unsignable);
        bridgeAnnouncedRelease(
            PegoutFixture.legacyPegout(PegoutFixture.standardFederation(keys, btcParams), btcParams, 1, PER_INPUT),
            CHAIN_HEIGHT - 10);

        client(federation).updateBridge();

        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).isEmpty();
        assertThat(bitcoin.broadcast()).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private BtcReleaseClient client(Federation federation) {
        BtcReleaseClient client = newClient();
        client.start(federation);
        return client;
    }

    private BtcReleaseClient newClient() {
        return new BtcReleaseClient(
            bitcoin,
            federatorSupport,
            bridgeConstants,
            signer,
            new PegoutOutpointValues(events, 50_000),
            events,
            new PegoutSignedCacheImpl(Duration.ofMinutes(30), Clock.systemUTC()),
            4_500);
    }

    /**
     * A proposed federation this sequencer belongs to: it holds an incoming member's keys.
     *
     * <p>Always P2SH-P2WSH, since that is the only kind a vote can create.
     */
    private Federation proposedFederation() {
        return PegoutFixture.segwitFederation(
            PegoutFixture.federationKeys(3), btcParams, bridgeConstants.getFederationConstants());
    }

    /** A proposed federation built from keys nobody here holds, as an outgoing member would see. */
    private Federation proposedFederationOfStrangers() {
        return PegoutFixture.segwitFederation(
            PegoutFixture.strangersKeys(3), btcParams, bridgeConstants.getFederationConstants());
    }

    private void svpIsWaitingOn(Hash creationRskTxHash, BtcTransaction svpSpend) {
        node.answering(BridgeMethods.GET_STATE_FOR_SVP_CLIENT,
            new co.rsk.peg.StateForProposedFederator(
                java.util.Map.entry(creationRskTxHash, svpSpend)).encodeToRlp());
    }

    private void bridgeIsWaitingOn(Hash creationRskTxHash, BtcTransaction pegout) {
        TreeMap<Hash, BtcTransaction> waiting = new TreeMap<>();
        waiting.put(creationRskTxHash, pegout);
        bridgeIsWaitingOn(waiting);
    }

    private void bridgeIsWaitingOn(TreeMap<Hash, BtcTransaction> waiting) {
        node.answering(BridgeMethods.GET_STATE_FOR_BTC_RELEASE_CLIENT,
            new StateForFederator(waiting).encodeToRlp());
    }

    /** As the bridge does when it builds a peg-out: announce what each input was worth. */
    private void bridgeAnnouncedValues(BtcTransaction pegout, List<Coin> values, long blockNumber) {
        BtcTransaction unsigned = BitcoinUtils.getMultiSigTransactionWithoutSignatures(pegout);
        node.emitting(
            blockNumber,
            BridgeEventReader.topicOf(BridgeEvents.PEGOUT_TRANSACTION_CREATED),
            List.of(Bytes32.wrap(unsigned.getHash().getBytes())),
            BridgeEvents.PEGOUT_TRANSACTION_CREATED.getEvent()
                .encodeEventData(UtxoUtils.encodeOutpointValues(values)));
    }

    /** As the bridge does when a peg-out has all the signatures it needs. */
    private void bridgeAnnouncedRelease(BtcTransaction finished, long blockNumber) {
        node.emitting(
            blockNumber,
            BridgeEventReader.topicOf(BridgeEvents.RELEASE_BTC),
            List.of(Bytes32.ZERO),
            BridgeEvents.RELEASE_BTC.getEvent().encodeEventData((Object) finished.bitcoinSerialize()));
    }

    /**
     * Another federator's real signature, which for a segwit input goes into the witness.
     *
     * <p>Encoded the way bitcoin encodes one — DER followed by the sighash flag — because the
     * bridge's own check parses it, and bytes that merely look like a signature do not exercise
     * the same path.
     */
    private void putSomebodyElsesSignatureInTheWitness(BtcTransaction pegout) {
        Sha256Hash sigHash = BitcoinUtils.generateSigHashForSegwitTransactionInput(pegout, 0, PER_INPUT);
        BtcECKey otherFederator = keys.get(1);
        co.rsk.bitcoinj.crypto.TransactionSignature signature =
            new co.rsk.bitcoinj.crypto.TransactionSignature(
                otherFederator.sign(sigHash), BtcTransaction.SigHash.ALL, false);

        co.rsk.bitcoinj.core.TransactionWitness existing = pegout.getWitness(0);
        co.rsk.bitcoinj.core.TransactionWitness signed =
            new co.rsk.bitcoinj.core.TransactionWitness(existing.getPushCount());
        for (int push = 0; push < existing.getPushCount(); push++) {
            signed.setPush(push, existing.getPush(push));
        }
        signed.setPush(1, signature.encodeToBitcoin());
        pegout.setWitness(0, signed);
    }

    /** This federator's own signature, placed in the input as the bridge would have. */
    private void putOurOwnSignatureInTheScriptSig(BtcTransaction pegout) throws Exception {
        Sha256Hash sigHash = BitcoinUtils.generateSigHashForLegacyTransactionInput(pegout, 0);
        co.rsk.bitcoinj.crypto.TransactionSignature signature =
            new co.rsk.bitcoinj.crypto.TransactionSignature(
                keys.get(0).sign(sigHash), BtcTransaction.SigHash.ALL, false);

        List<co.rsk.bitcoinj.script.ScriptChunk> chunks =
            new java.util.ArrayList<>(pegout.getInput(0).getScriptSig().getChunks());
        chunks.set(1, new co.rsk.bitcoinj.script.ScriptChunk(
            signature.encodeToBitcoin().length, signature.encodeToBitcoin()));
        pegout.getInput(0).setScriptSig(
            new co.rsk.bitcoinj.script.ScriptBuilder().addChunks(chunks).build());
    }

    /**
     * The signer, answering with whatever the distortion makes of its signature.
     *
     * <p>Stands in for a device. A key file cannot return a high-S signature or one over another
     * digest: bitcoinj signs and canonicalises in one step, from a key this process holds and can
     * check itself against. Everything this guards against arrives only once the answer comes from
     * somewhere else.
     */
    private ECDSASigner signerReturning(UnaryOperator<BtcECKey.ECDSASignature> distortion) {
        ECDSASigner delegate = signer;
        return new ECDSASigner() {
            @Override
            public boolean canSignWith(KeyId keyId) {
                return delegate.canSignWith(keyId);
            }

            @Override
            public List<String> check() {
                return delegate.check();
            }

            @Override
            public ECPublicKey getPublicKey(KeyId keyId) throws SignerException {
                return delegate.getPublicKey(keyId);
            }

            @Override
            public BtcECKey.ECDSASignature sign(KeyId keyId, Bytes32 digest) throws SignerException {
                return distortion.apply(delegate.sign(keyId, digest));
            }

            @Override
            public String describe() {
                return delegate.describe();
            }
        };
    }

    private BtcECKey federatorKey() throws Exception {
        return signer.getPublicKey(SequencerKeyId.BTC.getKeyId()).toBtcKey();
    }

    private static BtcECKey.ECDSASignature decode(byte[] der) {
        return BtcECKey.ECDSASignature.decodeFromDER(der);
    }

    private ECDSASigner federatorSigner() throws Exception {
        Path keyFile = home.resolve("btc.key");
        Files.writeString(keyFile, keys.get(0).getPrivateKeyAsHex());
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        return new ECDSASignerFromFileKey(SequencerKeyId.BTC.getKeyId(), keyFile.toString());
    }

    private BridgeClient bridgeClient() throws Exception {
        Path keyFile = home.resolve("rsk.key");
        Files.writeString(keyFile, RSK_KEY);
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        LegacyTransactionSigner txSigner = new LegacyTransactionSigner(
            new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()), new KeyId("RSK"), CHAIN_ID);
        return new BridgeClient(node, txSigner, GasPolicy.alwaysPaid(BigInteger.ONE), 4_000_000);
    }
}
