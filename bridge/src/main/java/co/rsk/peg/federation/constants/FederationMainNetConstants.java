package co.rsk.peg.federation.constants;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;

public class FederationMainNetConstants extends FederationConstants {

    private static final FederationMainNetConstants INSTANCE = new FederationMainNetConstants();

    private FederationMainNetConstants() {
        btcParams = NetworkParameters.fromID(NetworkParameters.ID_MAINNET);

        genesisFederationPublicKeys = Stream.of(
            "023847d84835723306c459bc5882c68e2d3f27a0612098e3755afb61c2f8c3eb56",
            "0239b0f123dccacce105361f41be1f0cbbbcc2d6fefed518df497452e5bc736a1d",
            "02ad7ba4fc5137534c5e39db6d860c66d13cf17a6a2575b391b80ac3ece86f0a8f",
            "02c5960b526d975567d5b8524bac1d4b4e0580b4df24de7bf1c34d177e6573c819",
            "031878a41184afb1bca4057f202c29a69a8d4f43b638d8c2a25ca7bd6a52be07e5",
            "031d4f6ce1dfc3a16689cb411b994f16245f2e4017f42897924dc9508d9f139517",
            "034b62a52b97d9e0c129a75ffa7853ad59a5a522aa53c2e68d005162e5a5bad194",
            "03636b01933d4bb272b844e991b26a8f4f33935c88720b4205629e6ef4ff44024f",
            "039e8b85f66ea7c6fd5b05b4e3a4521f7f8106aae6c63171e8aa87c3697b84af43"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).toList();
        genesisFederationCreationTime = ZonedDateTime.parse("1970-01-18T12:49:08.400Z").toInstant();

        List<BtcECKey> federationChangeAuthorizedKeys = Stream.of(
            "043e8087c22dce3e7720e6b2b91d087e0d7da92fb1a11230a2526f6715f58273f55e360142818df21720964da02a44b2cf37d701de23260df2a2c6c6197a1cd1f2",
            "043eebb5604c58cdae3a0d37a8b2260a66db6e7a4ed218691956f2487ec471feaf79985dd0735f16f62d85f5a1a3a01c9f14f61e983cda441d320b271081ce2bcd",
            "045d4e294fe9f8acb5d7404fc1ccc96aab59d2d45f2cdcfc737d5c0795d9c9d1012dc2b2f3e13c783ae4c000774e2c77b3b107ad3d65ac5cd33361c475ea146740",
            "043dd7f042f117c42eddd3adefd23d5a1c27f4c37acd3c49043d232f0a2de8f98a70b9b62509f12558273d6211b4b14e278ee66dfae69f8716f807ed31c1a5e21c",
            "046bcc9edc2d915ad31decff37fe5a13e4b0d7f05691166d48f13fcc3e63a9fd46f005cdeb3464dc4a983f5fbc94844e4da89a4e2bf8113b3c4d6697d92194f78d"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).toList();
        federationChangeAuthorizer = new AddressBasedAuthorizer(federationChangeAuthorizedKeys, AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY);

        validationPeriodDurationInBlocks = 160_000L;

        federationActivationAge = 360_000L;

        fundsMigrationAgeSinceActivationBegin = 0L;
        fundsMigrationAgeSinceActivationEnd = 90_000L;

        erpFedPubKeysList = Stream.of(
            "02224e4046efde81dba5f3b31dc3a8ca50882ca48b53e874f6ced34c2681c9250b",
            "0384b6011c2a78361b5490c72a81621eb63574df52ff521c3ca9a5123fa2b18165",
            "03946978b3db76f2658246e4194a2459db9e9780c1c2c5c304c8e199a62276ab83",
            "03d78ecf1cb25d91e5786fa85100b225266a41b7453bb7c46993ee749e154479ad"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).toList();
        erpFedActivationDelay = 52_560; // 1 year in BTC blocks (considering 1 block every 10 minutes)
    }

    public static FederationMainNetConstants getInstance() {
        return INSTANCE;
    }
}
