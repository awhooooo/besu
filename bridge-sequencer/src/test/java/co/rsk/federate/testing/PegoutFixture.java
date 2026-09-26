package co.rsk.federate.testing;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.TransactionWitness;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationArgs;
import co.rsk.peg.federation.FederationFactory;
import co.rsk.peg.federation.FederationMember;
import co.rsk.peg.federation.constants.FederationConstants;

/**
 * Peg-outs as the bridge hands them over: spending a federation, unsigned, in both the legacy and
 * the segwit shape.
 *
 * <p>The shape matters more than it looks. A legacy input carries the redeem script in its
 * scriptSig and its signature commits to no value; a segwit input carries it in the witness and
 * its signature commits to what the input was worth. Everything the release client decides follows
 * from telling those apart.
 */
public final class PegoutFixture {

    private PegoutFixture() {
    }

    /** Deterministic federation keys; the same list every time so a test can sign with them. */
    public static List<BtcECKey> federationKeys(int count) {
        List<BtcECKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(BtcECKey.fromPrivate(BigInteger.valueOf(501 + i)));
        }
        return keys;
    }

    /**
     * Keys belonging to nobody in {@link #federationKeys}.
     *
     * <p>A larger federationKeys list is a superset of a smaller one, which makes it useless for
     * asking whether a federator belongs somewhere: it always does.
     */
    public static List<BtcECKey> strangersKeys(int count) {
        List<BtcECKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(BtcECKey.fromPrivate(BigInteger.valueOf(9_001 + i)));
        }
        return keys;
    }

    /** A plain multisig federation, as a chain's genesis federation is. */
    public static Federation standardFederation(List<BtcECKey> keys, NetworkParameters btcParams) {
        return FederationFactory.buildStandardMultiSigFederation(args(keys, btcParams));
    }

    /** A P2SH-P2WSH federation, as one voted in is. */
    public static Federation segwitFederation(
        List<BtcECKey> keys, NetworkParameters btcParams, FederationConstants federationConstants) {
        return FederationFactory.buildP2shP2wshErpFederation(
            args(keys, btcParams),
            federationConstants.getErpFedPubKeysList(),
            federationConstants.getErpFedActivationDelay());
    }

    private static FederationArgs args(List<BtcECKey> keys, NetworkParameters btcParams) {
        return new FederationArgs(
            FederationMember.getFederationMembersFromKeys(keys),
            java.time.Instant.ofEpochSecond(1_700_000_000L),
            1L,
            btcParams);
    }

    /**
     * An unsigned peg-out spending {@code inputCount} of the federation's outputs, legacy style.
     *
     * <p>The scriptSig is what the bridge builds before anyone signs: an OP_0 placeholder for each
     * signature the redeem script will need, then the redeem script itself.
     */
    public static BtcTransaction legacyPegout(
        Federation federation, NetworkParameters btcParams, int inputCount, Coin perInput) {

        BtcTransaction pegout = new BtcTransaction(btcParams);
        Script redeemScript = federation.getRedeemScript();
        Script unsignedScriptSig = unsignedScriptSig(federation, redeemScript);

        for (int i = 0; i < inputCount; i++) {
            pegout.addInput(Sha256Hash.of(new byte[] {(byte) (70 + i)}), i, unsignedScriptSig);
        }
        pegout.addOutput(perInput.multiply(inputCount).subtract(Coin.MILLICOIN), someDestination(btcParams));
        return pegout;
    }

    /** The same, but segwit: the redeem script lives in the witness rather than the scriptSig. */
    public static BtcTransaction segwitPegout(
        Federation federation, NetworkParameters btcParams, int inputCount, Coin perInput) {

        BtcTransaction pegout = new BtcTransaction(btcParams);
        Script redeemScript = federation.getRedeemScript();

        for (int i = 0; i < inputCount; i++) {
            // A P2SH-P2WSH input's scriptSig is just the push of the witness program.
            Script witnessProgram = new ScriptBuilder()
                .data(new ScriptBuilder().number(0).data(Sha256Hash.hash(redeemScript.getProgram())).build().getProgram())
                .build();
            pegout.addInput(Sha256Hash.of(new byte[] {(byte) (80 + i)}), i, witnessProgram);

            TransactionWitness witness = new TransactionWitness(federation.getNumberOfSignaturesRequired() + 2);
            witness.setPush(0, new byte[0]);
            for (int slot = 1; slot <= federation.getNumberOfSignaturesRequired(); slot++) {
                witness.setPush(slot, new byte[0]);
            }
            witness.setPush(federation.getNumberOfSignaturesRequired() + 1, redeemScript.getProgram());
            pegout.setWitness(i, witness);
        }
        pegout.addOutput(perInput.multiply(inputCount).subtract(Coin.MILLICOIN), someDestination(btcParams));
        return pegout;
    }

    private static Script unsignedScriptSig(Federation federation, Script redeemScript) {
        ScriptBuilder builder = new ScriptBuilder().number(0);
        for (int slot = 0; slot < federation.getNumberOfSignaturesRequired(); slot++) {
            builder.number(0);
        }
        return builder.data(redeemScript.getProgram()).build();
    }

    private static co.rsk.bitcoinj.core.Address someDestination(NetworkParameters btcParams) {
        byte[] hash = new byte[20];
        java.util.Arrays.fill(hash, (byte) 0x2b);
        return new co.rsk.bitcoinj.core.Address(btcParams, hash);
    }

    /** What each of a peg-out's inputs was worth, as the bridge would have announced it. */
    public static List<Coin> outpointValues(int inputCount, Coin perInput) {
        List<Coin> values = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            values.add(perInput);
        }
        return values;
    }
}
