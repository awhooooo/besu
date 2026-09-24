package co.rsk.peg.lockingcap.constants;

import static org.junit.jupiter.api.Assertions.*;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.peg.utils.PublicKeys;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LockingCapConstantsTest {

    @ParameterizedTest
    @MethodSource("getIncreaseAuthorizerProvider")
    void getIncreaseAuthorizer(LockingCapConstants lockingCapConstants, AddressBasedAuthorizer expectedIncreaseAuthorizer, List<BtcECKey> expectedAuthorizedKeys) {
        AddressBasedAuthorizer actualIncreaseAuthorizer = lockingCapConstants.getIncreaseAuthorizer();
        assertEquals(expectedIncreaseAuthorizer.getNumberOfAuthorizedKeys(), actualIncreaseAuthorizer.getNumberOfAuthorizedKeys());
        assertEquals(expectedIncreaseAuthorizer.getRequiredAuthorizedKeys(), actualIncreaseAuthorizer.getRequiredAuthorizedKeys());
        for (BtcECKey authorizedKey : expectedAuthorizedKeys) {
            assertTrue(actualIncreaseAuthorizer.isAuthorized(PublicKeys.addressOf(authorizedKey)));
        }
    }

    private static Stream<Arguments> getIncreaseAuthorizerProvider() {
        // MainNet
        // RSKj's test held another key set here; its matcher only compared the key count, so it never noticed
        List<BtcECKey> mainNetIncreaseAuthorizedKeys = Arrays.stream(new String[]{
            "0498f27cfcb624a30bac2d826013727418ecacf6666f6456a1c20310f2def74596bc48a442749ebe4f074692dd30812565b4160a6d389a8d88b641b108ac9f8d5a",
            "0443fae6b1274fb9721996478a22af85fa43c8baeb0d2126ec2927a030afed4083c60abe0d11a0f52621615d471568498a89ce6b509dfe50523129e093cec29373",
            "044be7320865358340ba325a74212c851595c202e4df6593ec03d5f584e947332e48896c94072727c83876c7be8a49ed26f2b334d3c7a05c4083886109f1abc551"
        }).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        AddressBasedAuthorizer mainNetIncreaseAuthorizer = new AddressBasedAuthorizer(
            mainNetIncreaseAuthorizedKeys,
            AddressBasedAuthorizer.MinimumRequiredCalculation.ONE
        );

        // TestNet
        List<BtcECKey> testNetIncreaseAuthorizedKeys = Arrays.stream(new String[]{
            "04701d1d27f8c2ae97912d96fb1f82f10c2395fd320e7a869049268c6b53d2060dfb2e22e3248955332d88cd2ae29a398f8f3858e48dd6d8ffbc37dfd6d1aa4934",
            "045ef89e4a5645dc68895dbc33b4c966c3a0a52bb837ecdd2ba448604c4f47266456d1191420e1d32bbe8741f8315fde4d1440908d400e5998dbed6549d499559b",
            "0455db9b3867c14e84a6f58bd2165f13bfdba0703cb84ea85788373a6a109f3717e40483aa1f8ef947f435ccdf10e530dd8b3025aa2d4a7014f12180ee3a301d27"
        }).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        AddressBasedAuthorizer testNetIncreaseAuthorizer = new AddressBasedAuthorizer(
            testNetIncreaseAuthorizedKeys,
            AddressBasedAuthorizer.MinimumRequiredCalculation.ONE
        );

        // RegTest
        BtcECKey authorizerPublicKey = BtcECKey.fromPublicOnly(Hex.decode(
            "04450bbaab83ec48b3cb8fbb077c950ee079733041c039a8c4f1539e5181ca1a27589eeaf0fbf430e49d2909f14c767bf6909ad6845831f683416ee12b832e36ed"
        ));

        List<BtcECKey> regTestIncreaseAuthorizedKeys = Collections.singletonList(authorizerPublicKey);

        AddressBasedAuthorizer regTestIncreaseAuthorizer = new AddressBasedAuthorizer(
            regTestIncreaseAuthorizedKeys,
            AddressBasedAuthorizer.MinimumRequiredCalculation.ONE
        );

        return Stream.of(
            Arguments.of(LockingCapMainNetConstants.getInstance(), mainNetIncreaseAuthorizer, mainNetIncreaseAuthorizedKeys),
            Arguments.of(LockingCapTestNetConstants.getInstance(), testNetIncreaseAuthorizer, testNetIncreaseAuthorizedKeys),
            Arguments.of(LockingCapRegTestConstants.getInstance(), regTestIncreaseAuthorizer, regTestIncreaseAuthorizedKeys)
        );
    }

    @ParameterizedTest
    @MethodSource("getInitialValueProvider")
    void getInitialValue(LockingCapConstants lockingCapConstants, Coin expectedInitialValue) {
        Coin actualInitialValue = lockingCapConstants.getInitialValue();
        assertEquals(expectedInitialValue, actualInitialValue);
    }

    private static Stream<Arguments> getInitialValueProvider() {
        // MainNet
        Coin mainNetInitialValue = Coin.COIN.multiply(3000L);

        // TestNet
        Coin testNetInitialValue = Coin.COIN.multiply(200L);

        // RegTest
        Coin regTestInitialValue = Coin.COIN.multiply(1_000L);

        return Stream.of(
            Arguments.of(LockingCapMainNetConstants.getInstance(), mainNetInitialValue),
            Arguments.of(LockingCapTestNetConstants.getInstance(), testNetInitialValue),
            Arguments.of(LockingCapRegTestConstants.getInstance(), regTestInitialValue)
        );
    }

    @ParameterizedTest
    @MethodSource("getIncrementsMultiplierProvider")
    void getIncrementsMultiplier(LockingCapConstants lockingCapConstants, int expectedIncrementsMultiplier) {
        int actualIncrementsMultiplier = lockingCapConstants.getIncrementsMultiplier();
        assertEquals(expectedIncrementsMultiplier, actualIncrementsMultiplier);
    }

    private static Stream<Arguments> getIncrementsMultiplierProvider() {
        // MainNet
        int mainNetIncrementsMultiplier = 2;

        // TestNet
        int testNetIncrementsMultiplier = 2;

        // RegTest
        int regTestIncrementsMultiplier = 2;

        return Stream.of(
            Arguments.of(LockingCapMainNetConstants.getInstance(), mainNetIncrementsMultiplier),
            Arguments.of(LockingCapTestNetConstants.getInstance(), testNetIncrementsMultiplier),
            Arguments.of(LockingCapRegTestConstants.getInstance(), regTestIncrementsMultiplier)
        );
    }
}
