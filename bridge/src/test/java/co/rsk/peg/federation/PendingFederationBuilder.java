package co.rsk.peg.federation;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.peg.bitcoin.BitcoinTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Ported from RSKj's test builders. RSK and MST keys are BtcECKey here, as in the ported FederationMember. */
public class PendingFederationBuilder {
    private List<BtcECKey> membersBtcPublicKeys;
    private List<BtcECKey> membersRskPublicKeys;
    private List<BtcECKey> membersMstPublicKeys;

    private PendingFederationBuilder() {
        this.membersBtcPublicKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{"member01", "member02", "member03", "member04", "member05", "member06", "member07", "member08", "member09"}, true
        );
        this.membersRskPublicKeys = new ArrayList<>();
        this.membersMstPublicKeys = new ArrayList<>();
    }

    public static PendingFederationBuilder builder() {
        return new PendingFederationBuilder();
    }

    public PendingFederationBuilder withMembersBtcPublicKeys(List<BtcECKey> btcPublicKeys) {
        this.membersBtcPublicKeys = btcPublicKeys;
        return this;
    }

    public PendingFederationBuilder withMembersRskPublicKeys(List<BtcECKey> rskPublicKeys) {
        this.membersRskPublicKeys = rskPublicKeys;
        return this;
    }

    public PendingFederationBuilder withMembersMstPublicKeys(List<BtcECKey> mstPublicKeys) {
        this.membersMstPublicKeys = mstPublicKeys;
        return this;
    }

    public PendingFederation build() {
        List<FederationMember> federationMembers = getFederationMembers();
        return new PendingFederation(federationMembers);
    }

    private List<FederationMember> getFederationMembers() {

        if (membersRskPublicKeys.isEmpty()) {
            membersRskPublicKeys = membersBtcPublicKeys.stream()
                .map(btcKey -> BtcECKey.fromPublicOnly(btcKey.getPubKey()))
                .collect(Collectors.toList());
        }

        if (membersMstPublicKeys.isEmpty()) {
            membersMstPublicKeys = new ArrayList<>(membersRskPublicKeys);
        }

        return IntStream.range(0, membersBtcPublicKeys.size())
            .mapToObj(i -> new FederationMember(
                membersBtcPublicKeys.get(i),
                membersRskPublicKeys.get(i),
                membersMstPublicKeys.get(i)
            ))
            .collect(Collectors.toList());
    }
}
