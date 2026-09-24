package co.rsk.peg.feeperkb.constants;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import org.bouncycastle.util.encoders.Hex;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.peg.utils.PublicKeys;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FeePerKbConstantsTest {

    @ParameterizedTest
    @MethodSource("getGenesisFeePerKbProvider")
    void getGenesisFeePerKb(FeePerKbConstants feePerKbConstants, Coin expectedGenesisFeePerKb) {
        Coin actualGenesisFeePerKb = feePerKbConstants.getGenesisFeePerKb();

        assertEquals(expectedGenesisFeePerKb, actualGenesisFeePerKb);
    }

    private static Stream<Arguments> getGenesisFeePerKbProvider() {
        return Stream.of(
            Arguments.of(FeePerKbMainNetConstants.getInstance(), Coin.MILLICOIN.multiply(5)),
            Arguments.of(FeePerKbTestNetConstants.getInstance(), Coin.MILLICOIN),
            Arguments.of(FeePerKbRegTestConstants.getInstance(), Coin.MILLICOIN)
        );
    }

    @ParameterizedTest
    @MethodSource("getMaxFeePerKbProvider")
    void getMaxFeePerKb(FeePerKbConstants feePerKbConstants, Coin expectedMaxFeePerKb) {
        Coin actualMaxFeePerKb = feePerKbConstants.getMaxFeePerKb();

        assertEquals(expectedMaxFeePerKb, actualMaxFeePerKb);
    }

    private static Stream<Arguments> getMaxFeePerKbProvider() {
        return Stream.of(
            Arguments.of(FeePerKbMainNetConstants.getInstance(), Coin.valueOf(5_000_000L)),
            Arguments.of(FeePerKbTestNetConstants.getInstance(), Coin.valueOf(5_000_000L)),
            Arguments.of(FeePerKbRegTestConstants.getInstance(), Coin.valueOf(5_000_000L))
        );
    }

    @ParameterizedTest
    @MethodSource("getFeePerKbChangeAuthorizerProvider")
    void getFeePerKbChangeAuthorizer(FeePerKbConstants feePerKbConstants,
        AddressBasedAuthorizer expectedFeePerKbChangeAuthorizer, List<BtcECKey> expectedAuthorizedKeys) {
        AddressBasedAuthorizer actualFeePerKbChangeAuthorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();

        assertEquals(expectedFeePerKbChangeAuthorizer.getNumberOfAuthorizedKeys(), actualFeePerKbChangeAuthorizer.getNumberOfAuthorizedKeys());
        assertEquals(expectedFeePerKbChangeAuthorizer.getRequiredAuthorizedKeys(), actualFeePerKbChangeAuthorizer.getRequiredAuthorizedKeys());
        for (BtcECKey authorizedKey : expectedAuthorizedKeys) {
            assertTrue(actualFeePerKbChangeAuthorizer.isAuthorized(PublicKeys.addressOf(authorizedKey)));
        }
    }

    private static Stream<Arguments> getFeePerKbChangeAuthorizerProvider() {
        //MainNet
        // RSKj's test held another key set here; its matcher only compared the key count, so it never noticed
        List<BtcECKey> mainNetFeePerKbAuthorizedKeys = Arrays.stream(new String[]{
            "04d0d9f7a7b49ccf4ae5b95b0c34247daa59caeee392be3db6fb8c20dd7734d19bdb043fbd9b57b451a418b00aa4fc41f5bd7d6f813dae7733fe5e38f96bb0bd2c",
            "04fcd9b443608df6e2350d9dc9259210050ca3c3648798165caa6bfc5fa37b1061d0b0f8decf940b1c3c285941dc1e38b7b4960d431471a039dfcd598629c1fbf2",
            "048ab86490eb8a0b4c77c22966bfd78d42fbd31bfa38956869dc62794cf38b858ea0a7ec3e1d4ae4f40395b9cf3e4efbd1e249f803b775ca1561a09c70a4cee077"
        }).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        AddressBasedAuthorizer mainNetFeePerKbChangeAuthorizer = new AddressBasedAuthorizer(
            mainNetFeePerKbAuthorizedKeys,
            AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        //TestNet
        List<BtcECKey> testNetFeePerKbAuthorizedKeys = Arrays.stream(new String[]{
            "04701d1d27f8c2ae97912d96fb1f82f10c2395fd320e7a869049268c6b53d2060dfb2e22e3248955332d88cd2ae29a398f8f3858e48dd6d8ffbc37dfd6d1aa4934",
            "045ef89e4a5645dc68895dbc33b4c966c3a0a52bb837ecdd2ba448604c4f47266456d1191420e1d32bbe8741f8315fde4d1440908d400e5998dbed6549d499559b",
            "0455db9b3867c14e84a6f58bd2165f13bfdba0703cb84ea85788373a6a109f3717e40483aa1f8ef947f435ccdf10e530dd8b3025aa2d4a7014f12180ee3a301d27"
        }).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        AddressBasedAuthorizer testNetFeePerKbChangeAuthorizer = new AddressBasedAuthorizer(
            testNetFeePerKbAuthorizedKeys,
            AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        //RegTest
        List<BtcECKey> regTestFeePerKbAuthorizedKeys = Arrays.stream(new String[]{
            "0430c7d0146029db553d60cf11e8d39df1c63979ee2e4cd1e4d4289a5d88cfcbf3a09b06b5cbc88b5bfeb4b87a94cefab81c8d44655e7e813fc3e18f51cfe7e8a0"
        }).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        AddressBasedAuthorizer regTestFeePerKbChangeAuthorizer = new AddressBasedAuthorizer(
            regTestFeePerKbAuthorizedKeys,
            AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        return Stream.of(
            Arguments.of(FeePerKbMainNetConstants.getInstance(), mainNetFeePerKbChangeAuthorizer, mainNetFeePerKbAuthorizedKeys),
            Arguments.of(FeePerKbTestNetConstants.getInstance(), testNetFeePerKbChangeAuthorizer, testNetFeePerKbAuthorizedKeys),
            Arguments.of(FeePerKbRegTestConstants.getInstance(), regTestFeePerKbChangeAuthorizer, regTestFeePerKbAuthorizedKeys)
        );
    }
}
