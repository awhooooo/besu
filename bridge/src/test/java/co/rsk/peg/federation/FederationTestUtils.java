package co.rsk.peg.federation;

import co.rsk.bitcoinj.core.BtcECKey;

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
}
