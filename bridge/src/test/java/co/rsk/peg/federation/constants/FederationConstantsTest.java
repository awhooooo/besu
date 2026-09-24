package co.rsk.peg.federation.constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.constants.*;
import co.rsk.peg.utils.PublicKeys;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FederationConstantsTest {

    private static final FederationConstants MAINNET = FederationMainNetConstants.getInstance();
    private static final FederationConstants TESTNET = FederationTestNetConstants.getInstance();
    private static final FederationConstants REGTEST = new BridgeRegTestConstants().getFederationConstants();

    @ParameterizedTest
    @MethodSource("networkArgs")
    void getBtcParams(FederationConstants constants, NetworkParameters expectedNetwork) {
        assertEquals(expectedNetwork, constants.getBtcParams());
    }

    private static Stream<Arguments> networkArgs() {
        return Stream.of(
            Arguments.of(MAINNET, NetworkParameters.fromID(NetworkParameters.ID_MAINNET)),
            Arguments.of(TESTNET, NetworkParameters.fromID(NetworkParameters.ID_TESTNET)),
            Arguments.of(REGTEST, NetworkParameters.fromID(NetworkParameters.ID_REGTEST))
        );
    }

    @ParameterizedTest
    @MethodSource("genesisFedPublicKeysArgs")
    void getGenesisFederationPublicKeys(FederationConstants constants, List<BtcECKey> expectedPublicKeys) {
        assertEquals(expectedPublicKeys, constants.getGenesisFederationPublicKeys());
    }

    private static Stream<Arguments> genesisFedPublicKeysArgs() {
        List<BtcECKey> genesisFederationPublicKeysMainnet = Stream.of(
            "023847d84835723306c459bc5882c68e2d3f27a0612098e3755afb61c2f8c3eb56",
            "0239b0f123dccacce105361f41be1f0cbbbcc2d6fefed518df497452e5bc736a1d",
            "02ad7ba4fc5137534c5e39db6d860c66d13cf17a6a2575b391b80ac3ece86f0a8f",
            "02c5960b526d975567d5b8524bac1d4b4e0580b4df24de7bf1c34d177e6573c819",
            "031878a41184afb1bca4057f202c29a69a8d4f43b638d8c2a25ca7bd6a52be07e5",
            "031d4f6ce1dfc3a16689cb411b994f16245f2e4017f42897924dc9508d9f139517",
            "034b62a52b97d9e0c129a75ffa7853ad59a5a522aa53c2e68d005162e5a5bad194",
            "03636b01933d4bb272b844e991b26a8f4f33935c88720b4205629e6ef4ff44024f",
            "039e8b85f66ea7c6fd5b05b4e3a4521f7f8106aae6c63171e8aa87c3697b84af43"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        List<BtcECKey> genesisFederationPublicKeysTestnet = Stream.of(
            "039a060badbeb24bee49eb2063f616c0f0f0765d4ca646b20a88ce828f259fcdb9",
            "02afc230c2d355b1a577682b07bc2646041b5d0177af0f98395a46018da699b6da",
            "0344a3c38cd59afcba3edcebe143e025574594b001700dec41e59409bdbd0f2a09",
            "034844a99cd7028aa319476674cc381df006628be71bc5593b8b5fdb32bb42ef85"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        List<BtcECKey> genesisFederationPublicKeysRegtest = Stream.of(
            "0362634ab57dae9cb373a5d536e66a8c4f67468bbcfb063809bab643072d78a124",
            "03c5946b3fbae03a654237da863c9ed534e0878657175b132b8ca630f245df04db",
            "02cd53fc53a07f211641a677d250f6de99caf620e8e77071e811a28b3bcddf0be1"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        return Stream.of(
            Arguments.of(MAINNET, genesisFederationPublicKeysMainnet),
            Arguments.of(TESTNET, genesisFederationPublicKeysTestnet),
            Arguments.of(REGTEST, genesisFederationPublicKeysRegtest)
        );
    }

    @ParameterizedTest
    @MethodSource("genesisFedCreationTimeArgs")
    void getGenesisFedCreationTime(FederationConstants constants, Instant expectedCreationTime) {
        assertEquals(expectedCreationTime, constants.getGenesisFederationCreationTime());
    }

    private static Stream<Arguments> genesisFedCreationTimeArgs() {
        Instant genesisFedCreationTimeMainnet = ZonedDateTime.parse("1970-01-18T12:49:08.400Z").toInstant();
        Instant genesisFedCreationTimeTestnet = ZonedDateTime.parse("1970-01-18T19:29:27.600Z").toInstant();
        Instant genesisFedCreationTimeRegtest = ZonedDateTime.parse("2016-01-01T00:00:00Z").toInstant();

        return Stream.of(
            Arguments.of(MAINNET, genesisFedCreationTimeMainnet),
            Arguments.of(TESTNET, genesisFedCreationTimeTestnet),
            Arguments.of(REGTEST, genesisFedCreationTimeRegtest)
        );
    }

    @ParameterizedTest
    @MethodSource("validationPeriodDurationArgs")
    void getValidationPeriodDuration(FederationConstants constants, long expectedDuration) {
        assertEquals(expectedDuration, constants.getValidationPeriodDurationInBlocks());
    }

    private static Stream<Arguments> validationPeriodDurationArgs() {
        return Stream.of(
            Arguments.of(MAINNET, 160_000L),
            Arguments.of(TESTNET, 2000L),
            Arguments.of(REGTEST, 125L)
        );
    }

    @ParameterizedTest
    @MethodSource("fedActivationAgeArgs")
    void getFederationActivationAge(FederationConstants constants, long expectedActivationAge) {
        assertEquals(expectedActivationAge, constants.getFederationActivationAge());
    }

    private static Stream<Arguments> fedActivationAgeArgs() {
        long fedActivationAgeMainnet = 360_000L;
        long fedActivationAgeTestnetPostLovell = 2400L;
        long fedActivationAgeRegtest = 150L;

        return Stream.of(
            Arguments.of(MAINNET, fedActivationAgeMainnet),
            Arguments.of(TESTNET, fedActivationAgeTestnetPostLovell),
            Arguments.of(REGTEST, fedActivationAgeRegtest)
        );
    }

    @ParameterizedTest
    @MethodSource("fundsMigrationAgeSinceActivationBeginArgs")
    void getFundsMigrationAgeSinceActivationBegin(FederationConstants constants, long expectedFundsMigrationAge) {
        assertEquals(expectedFundsMigrationAge, constants.getFundsMigrationAgeSinceActivationBegin());
    }

    private static Stream<Arguments> fundsMigrationAgeSinceActivationBeginArgs() {
        long fundsMigrationAgeSinceActivationBeginMainnet = 0L;
        long fundsMigrationAgeSinceActivationBeginTestnet = 60L;
        long fundsMigrationAgeSinceActivationBeginRegtest = 15L;

        return Stream.of(
            Arguments.of(MAINNET, fundsMigrationAgeSinceActivationBeginMainnet),
            Arguments.of(TESTNET, fundsMigrationAgeSinceActivationBeginTestnet),
            Arguments.of(REGTEST, fundsMigrationAgeSinceActivationBeginRegtest)
        );
    }

    @ParameterizedTest
    @MethodSource("fundsMigrationAgeSinceActivationEndArgs")
    void getFundsMigrationAgeSinceActivationEnd(FederationConstants constants, long expectedActivationAge) {
        assertEquals(expectedActivationAge, constants.getFundsMigrationAgeSinceActivationEnd());
    }

    private static Stream<Arguments> fundsMigrationAgeSinceActivationEndArgs() {
        long fundsMigrationAgeSinceActivationEndMainnet = 90_000L;
        long fundsMigrationAgeSinceActivationEndTestnet = 900L;
        long fundsMigrationAgeSinceActivationEndRegtest = 150L;

        return Stream.of(
            Arguments.of(MAINNET, fundsMigrationAgeSinceActivationEndMainnet),
            Arguments.of(TESTNET, fundsMigrationAgeSinceActivationEndTestnet),
            Arguments.of(REGTEST, fundsMigrationAgeSinceActivationEndRegtest)
        );
    }

    @ParameterizedTest
    @MethodSource("fedChangeAuthorizerArgs")
    void getFederationChangeAuthorizer(FederationConstants constants, List<BtcECKey> expectedKeys) {
        AddressBasedAuthorizer expectedAuthorizer =
            new AddressBasedAuthorizer(expectedKeys, AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY);
        AddressBasedAuthorizer actualAuthorizer = constants.getFederationChangeAuthorizer();

        assertEquals(expectedAuthorizer.getNumberOfAuthorizedKeys(), actualAuthorizer.getNumberOfAuthorizedKeys());
        assertEquals(expectedAuthorizer.getRequiredAuthorizedKeys(), actualAuthorizer.getRequiredAuthorizedKeys());
        for (BtcECKey authorizedKey : expectedKeys) {
            assertTrue(actualAuthorizer.isAuthorized(PublicKeys.addressOf(authorizedKey)));
        }
    }

    private static Stream<Arguments> fedChangeAuthorizerArgs() {
        List<BtcECKey> fedChangeAuthorizedKeysMainnet =  Stream.of(
            "043e8087c22dce3e7720e6b2b91d087e0d7da92fb1a11230a2526f6715f58273f55e360142818df21720964da02a44b2cf37d701de23260df2a2c6c6197a1cd1f2",
            "043eebb5604c58cdae3a0d37a8b2260a66db6e7a4ed218691956f2487ec471feaf79985dd0735f16f62d85f5a1a3a01c9f14f61e983cda441d320b271081ce2bcd",
            "045d4e294fe9f8acb5d7404fc1ccc96aab59d2d45f2cdcfc737d5c0795d9c9d1012dc2b2f3e13c783ae4c000774e2c77b3b107ad3d65ac5cd33361c475ea146740",
            "043dd7f042f117c42eddd3adefd23d5a1c27f4c37acd3c49043d232f0a2de8f98a70b9b62509f12558273d6211b4b14e278ee66dfae69f8716f807ed31c1a5e21c",
            "046bcc9edc2d915ad31decff37fe5a13e4b0d7f05691166d48f13fcc3e63a9fd46f005cdeb3464dc4a983f5fbc94844e4da89a4e2bf8113b3c4d6697d92194f78d"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        List<BtcECKey> fedChangeAuthorizedKeysTestnet = Stream.of(
            "04d9052c2022f6f35da53f04f02856ff5e59f9836eec03daad0328d12c5c66140205da540498e46cd05bf63c1201382dd84c100f0d52a10654159965aea452c3f2",
            "04bf889f2035c8c441d7d1054b6a449742edd04d202f44a29348b4140b34e2a81ce66e388f40046636fd012bd7e3cecd9b951ffe28422334722d20a1cf6c7926fb",
            "047e707e4f67655c40c539363fb435d89574b8fe400971ba0290de9c2adbb2bd4e1e5b35a2188b9409ff2cc102292616efc113623483056bb8d8a02bf7695670ea"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        List<BtcECKey> fedChangeAuthorizedKeysRegtest = Stream.of(
            "04dde17c5fab31ffc53c91c2390136c325bb8690dc135b0840075dd7b86910d8ab9e88baad0c32f3eea8833446a6bc5ff1cd2efa99ecb17801bcb65fc16fc7d991",
            "04af886c67231476807e2a8eee9193878b9d94e30aa2ee469a9611d20e1e1c1b438e5044148f65e6e61bf03e9d72e597cb9cdea96d6fc044001b22099f9ec403e2",
            "045d4dedf9c69ab3ea139d0f0da0ad00160b7663d01ce7a6155cd44a3567d360112b0480ab6f31cac7345b5f64862205ea7ccf555fcf218f87fa0d801008fecb61",
            "04709f002ac4642b6a87ea0a9dc76eeaa93f71b3185985817ec1827eae34b46b5d869320efb5c5cbe2a5c13f96463fe0210710b53352a4314188daffe07bd54154",
            "04aff62315e9c18004392a5d9e39496ff5794b2d9f43ab4e8ade64740d7fdfe896969be859b43f26ef5aa4b5a0d11808277b4abfa1a07cc39f2839b89cc2bc6b4c"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        return Stream.of(
            Arguments.of(MAINNET, fedChangeAuthorizedKeysMainnet),
            Arguments.of(TESTNET, fedChangeAuthorizedKeysTestnet),
            Arguments.of(REGTEST, fedChangeAuthorizedKeysRegtest)
        );
    }

    @ParameterizedTest
    @MethodSource("erpActivationDelayArgs")
    void getErpFedActivationDelay(FederationConstants constants, long expectedActivationDelay) {
        assertEquals(expectedActivationDelay, constants.getErpFedActivationDelay());
    }

    private static Stream<Arguments> erpActivationDelayArgs() {
        long erpActivationDelayMainnet = 52_560;
        long erpActivationDelayTestnet = 52_560;
        long erpActivationDelayRegtest = 500;

        return Stream.of(
            Arguments.of(MAINNET, erpActivationDelayMainnet),
            Arguments.of(TESTNET, erpActivationDelayTestnet),
            Arguments.of(REGTEST, erpActivationDelayRegtest)
        );
    }

    @ParameterizedTest
    @MethodSource("erpFedPubKeysArgs")
    void getErpFedPubKeysList(FederationConstants constants, List<BtcECKey> expectedKeys) {
        assertEquals(expectedKeys, constants.getErpFedPubKeysList());
    }

    private static Stream<Arguments> erpFedPubKeysArgs() {
        List<BtcECKey> erpFedPubKeysMainnet = Stream.of(
            "02224e4046efde81dba5f3b31dc3a8ca50882ca48b53e874f6ced34c2681c9250b",
            "0384b6011c2a78361b5490c72a81621eb63574df52ff521c3ca9a5123fa2b18165",
            "03946978b3db76f2658246e4194a2459db9e9780c1c2c5c304c8e199a62276ab83",
            "03d78ecf1cb25d91e5786fa85100b225266a41b7453bb7c46993ee749e154479ad"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        List<BtcECKey> erpFedPubKeysTestnet = Stream.of(
            "0216c23b2ea8e4f11c3f9e22711addb1d16a93964796913830856b568cc3ea21d3",
            "034db69f2112f4fb1bb6141bf6e2bd6631f0484d0bd95b16767902c9fe219d4a6f",
            "0275562901dd8faae20de0a4166362a4f82188db77dbed4ca887422ea1ec185f14"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        List<BtcECKey> erpFedPubKeysRegtest = Stream.of(
            "03b9fc46657cf72a1afa007ecf431de1cd27ff5cc8829fa625b66ca47b967e6b24",
            "029cecea902067992d52c38b28bf0bb2345bda9b21eca76b16a17c477a64e43301",
            "03284178e5fbcc63c54c3b38e3ef88adf2da6c526313650041b0ef955763634ebd",
            "03ab0e2cd7ed158687fc13b88019990860cdb72b1f5777b58513312550ea1584bc"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        return Stream.of(
            Arguments.of(MAINNET, erpFedPubKeysMainnet),
            Arguments.of(TESTNET, erpFedPubKeysTestnet),
            Arguments.of(REGTEST, erpFedPubKeysRegtest)
        );
    }
}
