package co.rsk.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.test.builders.BridgeBuilder;
import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ported from RSKj. The gas a bridge call costs is read straight off the bridge, so RSKj's signed
 * transaction, execution block and repository are not needed: the cost depends only on the call data.
 * RSKIP124 and RSKIP132 are active, so only the dynamic cost with the reviewed constants is left.
 */
class BridgeCostsTest {
    private BridgeRegTestConstants bridgeConstants;

    @BeforeEach
    void setUp() {
        bridgeConstants = new BridgeRegTestConstants();
    }

    @Test
    void receiveHeadersGasCost() {
        Bridge bridge = new BridgeBuilder()
            .bridgeConstants(bridgeConstants)
            .build();

        final long BASE_COST = 25_000L;
        for (int numberOfHeaders = 0; numberOfHeaders < 10; numberOfHeaders++) {
            byte[][] headers = new byte[numberOfHeaders][];
            for (int i = 0; i < numberOfHeaders; i++) {
                headers[i] = Hex.decode("00112233445566778899");
            }

            Bytes data = BridgeMethods.RECEIVE_HEADERS.getFunction().encode((Object) headers);

            long cost = BASE_COST + 2L * data.size();
            if (numberOfHeaders > 1) {
                cost += 3500L * (numberOfHeaders - 1);
            }
            assertEquals(cost, bridge.getGasForData(data));
        }
    }

    @Test
    void getGasForDataInvalidFunction() {
        getGasForDataPaidTx(23000, null);
    }

    @Test
    void getGasForDataUpdateCollections() {
        getGasForDataPaidTx(48000 + 8, BridgeMethods.UPDATE_COLLECTIONS.getFunction());
    }

    @Test
    void getGasForDataRegisterBtcTransaction() {
        getGasForDataPaidTx(22000 + 228 * 2, BridgeMethods.REGISTER_BTC_TRANSACTION.getFunction(), new byte[3], 1, new byte[3]);
    }

    @Test
    void getGasForDataReleaseBtc() {
        getGasForDataPaidTx(23000 + 8, BridgeMethods.RELEASE_BTC.getFunction());
    }

    @Test
    void getGasForDataAddSignature() {
        getGasForDataPaidTx(70000 + 548 * 2, BridgeMethods.ADD_SIGNATURE.getFunction(), new byte[3], new byte[3][2], new byte[3]);
    }

    @Test
    void getGasForDataGSFBRC() {
        getGasForDataPaidTx(4000 + 8, BridgeMethods.GET_STATE_FOR_BTC_RELEASE_CLIENT.getFunction());
    }

    @Test
    void getGasForDataGSFD() {
        getGasForDataPaidTx(3_000_000 + 8, BridgeMethods.GET_STATE_FOR_DEBUGGING.getFunction());
    }

    @Test
    void getGasForDataGBBBCH() {
        getGasForDataPaidTx(19000 + 8, BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.getFunction());
    }

    @Test
    void getGasForDataGetFederationAddress() {
        getGasForDataPaidTx(11000 + 8, BridgeMethods.GET_FEDERATION_ADDRESS.getFunction());
    }

    @Test
    void getGasForDataGetMinimumLockTxValue() {
        getGasForDataPaidTx(2000 + 8, BridgeMethods.GET_MINIMUM_LOCK_TX_VALUE.getFunction());
    }

    private void getGasForDataPaidTx(int expected, AbiFunction function, Object... funcArgs) {
        Bridge bridge = new BridgeBuilder()
            .bridgeConstants(bridgeConstants)
            .build();

        // RSKj encoded a call with no arguments as the selector alone, even for a function that takes some
        Bytes data = function == null
            ? Bytes.wrap(new byte[]{1, 2, 3})
            : funcArgs.length == 0 ? function.encodeSignature() : function.encode(funcArgs);

        assertEquals(expected, bridge.getGasForData(data));
    }
}
