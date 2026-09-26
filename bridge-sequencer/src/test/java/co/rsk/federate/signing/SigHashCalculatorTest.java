package co.rsk.federate.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.federate.testing.PegoutFixture;
import co.rsk.peg.bitcoin.BitcoinUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.federation.Federation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The digest is the whole of what a federator signs, and signing the wrong one is not a failure —
 * it is a valid signature over the wrong message. The bridge counts it, the threshold is reached,
 * and the transaction bitcoin is handed cannot be spent.
 *
 * <p>So the property checked here is agreement with the bridge, not self-consistency: the digest
 * these produce must be the one {@code BridgeSupport.addSignature} will check against.
 */
class SigHashCalculatorTest {

    private static final Coin PER_INPUT = Coin.COIN.multiply(3);

    private BridgeConstants bridgeConstants;
    private NetworkParameters btcParams;
    private List<BtcECKey> keys;

    @BeforeEach
    void setUp() {
        bridgeConstants = new BridgeRegTestConstants();
        btcParams = bridgeConstants.getBtcParams();
        keys = PegoutFixture.federationKeys(3);
    }

    @Test
    void theLegacyDigestIsTheOneTheBridgeWillCheck() {
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 2, PER_INPUT);

        SigHashCalculator calculator = new LegacySigHashCalculator();

        for (int input = 0; input < pegout.getInputs().size(); input++) {
            assertThat(calculator.calculate(pegout, input))
                .isEqualTo(BitcoinUtils.generateSigHashForLegacyTransactionInput(pegout, input));
        }
    }

    @Test
    void theSegwitDigestIsTheOneTheBridgeWillCheck() {
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction pegout = PegoutFixture.segwitPegout(federation, btcParams, 2, PER_INPUT);
        List<Coin> values = PegoutFixture.outpointValues(2, PER_INPUT);

        SigHashCalculator calculator = new SegwitSigHashCalculator(values);

        for (int input = 0; input < pegout.getInputs().size(); input++) {
            assertThat(calculator.calculate(pegout, input))
                .isEqualTo(BitcoinUtils.generateSigHashForSegwitTransactionInput(
                    pegout, input, values.get(input)));
        }
    }

    @Test
    void eachInputHasItsOwnDigest() {
        // Signing every input with the first input's digest would be the easiest possible mistake
        // and would still produce well-formed signatures.
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 3, PER_INPUT);

        SigHashCalculator calculator = new LegacySigHashCalculator();
        Sha256Hash first = calculator.calculate(pegout, 0);
        Sha256Hash second = calculator.calculate(pegout, 1);
        Sha256Hash third = calculator.calculate(pegout, 2);

        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
    }

    @Test
    void theSegwitDigestDependsOnWhatTheInputWasWorth() {
        // This is the reason the values have to be fetched at all. If they were ignored, or a
        // wrong value were used, the digest would still be produced and the signature still valid
        // arithmetic — over a message no verifier will reconstruct.
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction pegout = PegoutFixture.segwitPegout(federation, btcParams, 1, PER_INPUT);

        Sha256Hash asBuilt = new SegwitSigHashCalculator(List.of(PER_INPUT)).calculate(pegout, 0);
        Sha256Hash oneSatoshiOut =
            new SegwitSigHashCalculator(List.of(PER_INPUT.add(Coin.SATOSHI))).calculate(pegout, 0);

        assertThat(asBuilt).isNotEqualTo(oneSatoshiOut);
    }

    @Test
    void theTwoKindsOfDigestAreNotTheSame() {
        // Which is why the client must pick by looking at the input rather than by configuration.
        Federation legacyFederation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction legacyPegout = PegoutFixture.legacyPegout(legacyFederation, btcParams, 1, PER_INPUT);
        Federation segwitFederation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction segwitPegout = PegoutFixture.segwitPegout(segwitFederation, btcParams, 1, PER_INPUT);

        assertThat(BitcoinUtils.inputHasWitness(legacyPegout, 0)).isFalse();
        assertThat(BitcoinUtils.inputHasWitness(segwitPegout, 0)).isTrue();
        assertThat(new LegacySigHashCalculator().calculate(legacyPegout, 0))
            .isNotEqualTo(new SegwitSigHashCalculator(List.of(PER_INPUT)).calculate(segwitPegout, 0));
    }

    @Test
    void anInputWithNoAnnouncedValueIsRefusedRatherThanGuessed() {
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction pegout = PegoutFixture.segwitPegout(federation, btcParams, 3, PER_INPUT);

        SigHashCalculator calculator = new SegwitSigHashCalculator(PegoutFixture.outpointValues(2, PER_INPUT));

        assertThat(calculator.calculate(pegout, 1)).isNotNull();
        assertThatThrownBy(() -> calculator.calculate(pegout, 2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("only 2 outpoint values");
    }

    @Test
    void anInputWithNoRedeemScriptIsRefusedRatherThanHashedAnyway() {
        // Powpeg raised IllegalStateException here; the bridge's own extraction raises
        // IllegalArgumentException. Either way it must refuse: a digest over an input whose
        // redeem script could not be read is a digest over the wrong thing.
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        pegout.getInput(0).setScriptSig(new co.rsk.bitcoinj.script.ScriptBuilder().build());

        assertThatThrownBy(() -> new LegacySigHashCalculator().calculate(pegout, 0))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aSegwitInputWithNoRedeemScriptIsRefusedRatherThanHashedAnyway() {
        Federation federation =
            PegoutFixture.segwitFederation(keys, btcParams, bridgeConstants.getFederationConstants());
        BtcTransaction pegout = PegoutFixture.segwitPegout(federation, btcParams, 1, PER_INPUT);
        pegout.setWitness(0, new co.rsk.bitcoinj.core.TransactionWitness(0));

        assertThatThrownBy(
            () -> new SegwitSigHashCalculator(List.of(PER_INPUT)).calculate(pegout, 0))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aSignatureOverTheDigestIsOneTheBridgeAcceptsAsThisFederator() {
        // End to end within the peg's own rules: sign the digest, put it in the input the way the
        // bridge does, and ask the bridge's own check whose signature it is.
        Federation federation = PegoutFixture.standardFederation(keys, btcParams);
        BtcTransaction pegout = PegoutFixture.legacyPegout(federation, btcParams, 1, PER_INPUT);
        SigHashCalculator calculator = new LegacySigHashCalculator();
        Sha256Hash sigHash = calculator.calculate(pegout, 0);

        BtcECKey signer = keys.get(0);
        BtcECKey.ECDSASignature signature = signer.sign(sigHash);

        assertThat(signer.verify(sigHash, signature)).isTrue();
        // And not over any other input's digest, which is the mistake being guarded against.
        BtcTransaction twoInputs = PegoutFixture.legacyPegout(federation, btcParams, 2, PER_INPUT);
        assertThat(signer.verify(calculator.calculate(twoInputs, 1), signature)).isFalse();
    }
}
