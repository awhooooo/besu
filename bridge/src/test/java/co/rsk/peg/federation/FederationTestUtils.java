package co.rsk.peg.federation;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.bitcoin.BitcoinTestUtils;

import java.time.Instant;
import java.util.List;

/** Ported from RSKj's test utilities; only the members the bridge tests use. */
public final class FederationTestUtils {

    private FederationTestUtils() {
    }

    /** Members with the given BTC keys and random RSK and MST keys. */
    public static List<FederationMember> getFederationMembersWithBtcKeys(List<BtcECKey> keys) {
        return keys.stream()
            .map(btcKey -> new FederationMember(btcKey, new BtcECKey(), new BtcECKey()))
            .toList();
    }

    /** Members whose RSK and MST keys are the BTC key itself. */
    public static List<FederationMember> getFederationMembersWithKeys(List<BtcECKey> pks) {
        return pks.stream().map(FederationTestUtils::getFederationMemberWithKey).toList();
    }

    public static FederationMember getFederationMemberWithKey(BtcECKey pk) {
        return new FederationMember(pk, pk, pk);
    }

    public static ErpFederation getErpFederation(NetworkParameters networkParameters) {
        final List<BtcECKey> fedSigners = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{"fa01", "fa02", "fa03", "fa04", "fa05", "fa06", "fa07", "fa08", "fa09"}, true
        );

        return getErpFederationWithPrivKeys(networkParameters, fedSigners);
    }

    public static ErpFederation getErpFederationWithPrivKeys(NetworkParameters networkParameters, List<BtcECKey> fedSigners) {
        final List<BtcECKey> erpSigners = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{"fb01", "fb02", "fb03", "fb04"}, true
        );

        List<FederationMember> fedMember = FederationTestUtils.getFederationMembersWithBtcKeys(
            fedSigners
        );

        FederationArgs federationArgs = new FederationArgs(
            fedMember,
            Instant.ofEpochMilli(0),
            0,
            networkParameters
        );

        long erpFedActivationDelay = 52_560; // Mainnet value

        return FederationFactory.buildP2shErpFederation(
            federationArgs,
            erpSigners,
            erpFedActivationDelay
        );
    }
}
