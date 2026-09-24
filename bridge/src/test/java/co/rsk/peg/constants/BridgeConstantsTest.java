package co.rsk.peg.constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.federation.constants.*;
import co.rsk.peg.feeperkb.constants.*;
import co.rsk.peg.lockingcap.constants.*;
import java.util.stream.Stream;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BridgeConstantsTest {
    private static Stream<Arguments> minimumPeginTxValueArgProvider() {
        return Stream.of(
            Arguments.of(BridgeMainNetConstants.getInstance(), Coin.FIFTY_COINS.multiply(10)),
            Arguments.of(BridgeTestNetConstants.getInstance(), Coin.valueOf(500_000)),
            Arguments.of(new BridgeRegTestConstants(), Coin.valueOf(500_000))
        );
    }

    @ParameterizedTest()
    @MethodSource("minimumPeginTxValueArgProvider")
    void getMinimumPeginTxValue(BridgeConstants bridgeConstants, Coin expectedMinimumPeginTxValue) {
        Coin minimumPeginTxValue = bridgeConstants.getMinimumPeginTxValue();
        assertEquals(expectedMinimumPeginTxValue, minimumPeginTxValue);
    }

    private static Stream<Arguments> minimumPegoutTxValueArgProvider() {
        return Stream.of(
            Arguments.of(BridgeMainNetConstants.getInstance(), Coin.FIFTY_COINS.multiply(10)),
            Arguments.of(BridgeTestNetConstants.getInstance(), Coin.valueOf(250_000)),
            Arguments.of(new BridgeRegTestConstants(), Coin.valueOf(250_000))
        );
    }

    @ParameterizedTest()
    @MethodSource("minimumPegoutTxValueArgProvider")
    void getMinimumPegoutTxValue(BridgeConstants bridgeConstants, Coin expectedMinimumPegoutTxValue) {
        Coin minimumPegoutTxValue = bridgeConstants.getMinimumPegoutTxValue();
        assertEquals(expectedMinimumPegoutTxValue, minimumPegoutTxValue);
    }

    private static Stream<Arguments> svpFundTxOutputsValueArgProvider() {
        BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
        BridgeConstants bridgeTestnetConstants = BridgeTestNetConstants.getInstance();
        BridgeConstants bridgeRegtestConstants = new BridgeRegTestConstants();
        return Stream.of(
            Arguments.of(bridgeMainnetConstants, Coin.COIN.multiply(2)),
            Arguments.of(bridgeTestnetConstants, Coin.valueOf(500_000)),
            Arguments.of(bridgeRegtestConstants, Coin.valueOf(500_000))
        );
    }

    @ParameterizedTest()
    @MethodSource("svpFundTxOutputsValueArgProvider")
    void getSvpFundTxOutputsValue(BridgeConstants bridgeConstants, Coin expectedSvpFundTxOutputsValue) {
        assertEquals(expectedSvpFundTxOutputsValue, bridgeConstants.getSvpFundTxOutputsValue());
    }

    private static Stream<BridgeConstants> bridgeConstantsArgProvider() {
        BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
        BridgeConstants bridgeTestnetConstants = BridgeTestNetConstants.getInstance();
        BridgeConstants bridgeRegtestConstants = new BridgeRegTestConstants();

        return Stream.of(bridgeMainnetConstants, bridgeTestnetConstants, bridgeRegtestConstants);
    }

    @ParameterizedTest()
    @MethodSource("bridgeConstantsArgProvider")
    void getProposedFederationFlyoverPrefix(BridgeConstants bridgeConstants) {
        Hash expectedProposedFederationFlyoverPrefix = Hash.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001");

        assertEquals(expectedProposedFederationFlyoverPrefix, bridgeConstants.getProposedFederationFlyoverPrefix());
    }

    @ParameterizedTest()
    @MethodSource("getFeePerKbConstantsProvider")
    void getFeePerKbConstants(BridgeConstants bridgeConstants, FeePerKbConstants expectedValue) {
        // Act
        FeePerKbConstants actualFeePerKbConstants = bridgeConstants.getFeePerKbConstants();

        // Assert
        assertInstanceOf(expectedValue.getClass(), actualFeePerKbConstants);
    }

    private static Stream<Arguments> getFeePerKbConstantsProvider() {
        return Stream.of(
            Arguments.of(BridgeMainNetConstants.getInstance(), FeePerKbMainNetConstants.getInstance()),
            Arguments.of(BridgeTestNetConstants.getInstance(), FeePerKbTestNetConstants.getInstance()),
            Arguments.of(new BridgeRegTestConstants(), FeePerKbRegTestConstants.getInstance())
        );
    }

    @ParameterizedTest()
    @MethodSource("getFederationConstantsProvider")
    void getFederationConstants(BridgeConstants bridgeConstants, FederationConstants expectedValue) {
        // Act
        FederationConstants actualFederationConstants = bridgeConstants.getFederationConstants();

        // Assert
        assertInstanceOf(expectedValue.getClass(), actualFederationConstants);
    }

    private static Stream<Arguments> getFederationConstantsProvider() {
        BridgeConstants bridgeRegTestConstants = new BridgeRegTestConstants();
        FederationConstants federationRegTestConstants = bridgeRegTestConstants.getFederationConstants();
        return Stream.of(
            Arguments.of(BridgeMainNetConstants.getInstance(), FederationMainNetConstants.getInstance()),
            Arguments.of(BridgeTestNetConstants.getInstance(), FederationTestNetConstants.getInstance()),
            Arguments.of(bridgeRegTestConstants, FederationRegTestConstants.getInstance(federationRegTestConstants.getGenesisFederationPublicKeys()))
        );
    }

    @ParameterizedTest()
    @MethodSource("getLockingCapConstantsProvider")
    void getLockingCapConstants(BridgeConstants bridgeConstants, LockingCapConstants expectedValue){
        // Act
        LockingCapConstants actualLockingCapConstants = bridgeConstants.getLockingCapConstants();

        // Assert
        assertInstanceOf(expectedValue.getClass(), actualLockingCapConstants);
    }

    private static Stream<Arguments> getLockingCapConstantsProvider() {
        return Stream.of(
            Arguments.of(BridgeMainNetConstants.getInstance(), LockingCapMainNetConstants.getInstance()),
            Arguments.of(BridgeTestNetConstants.getInstance(), LockingCapTestNetConstants.getInstance()),
            Arguments.of(new BridgeRegTestConstants(), LockingCapRegTestConstants.getInstance())
        );
    }
}
