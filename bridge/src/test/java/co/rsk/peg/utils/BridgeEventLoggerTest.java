package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import co.rsk.RskTestUtils;
import co.rsk.bitcoinj.core.*;
import co.rsk.peg.pegin.RejectedPeginReason;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeEventLoggerTest {
    private static final org.hyperledger.besu.datatypes.Address SENDER = org.hyperledger.besu.datatypes.Address.fromHexString("0x0000000000000000000000000000000000001101");

    private BridgeEventLogger eventLogger;
    private BtcTransaction btcTxMock;
    private Hash rskTxHash;

    @BeforeEach
    void setup() {
        eventLogger = spy(BridgeEventLogger.class);
        btcTxMock = mock(BtcTransaction.class);
        rskTxHash = RskTestUtils.createHash(1);
    }

    @Test
    void logPeginBtc() {
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logPeginBtc(
            SENDER,
            btcTxMock,
            Coin.SATOSHI,
            1
        ));
    }

    @Test
    void logReleaseBtcRequested() {
        byte[] rskTxHashBytes = rskTxHash.getBytes().toArrayUnsafe();
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logReleaseBtcRequested(
            rskTxHashBytes,
            btcTxMock,
            Coin.SATOSHI
        ));
    }

    @Test
    void logRejectedPegin() {
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logRejectedPegin(
            btcTxMock,
            RejectedPeginReason.PEGIN_CAP_SURPASSED
        ));
    }

    @Test
    void logNonRefundablePegin() {
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logNonRefundablePegin(
            btcTxMock,
            NonRefundablePeginReason.LEGACY_PEGIN_UNDETERMINED_SENDER
        ));
    }

    @Test
    void logReleaseBtcRequestReceived() {
        String base58Address = "16SL1Qsw1eyYWM58MFh9KwKYoxYmm3fM1Z";
        Address btcDestinationAddress = Address.fromBase58(
            NetworkParameters.fromID(NetworkParameters.ID_MAINNET),
            base58Address
        );
        Wei amount = Weis.fromSatoshis(Coin.COIN);
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logReleaseBtcRequestReceived(
            SENDER,
            btcDestinationAddress,
            amount
        ));
    }

    @Test
    void logReleaseBtcRequestRejected() {
        org.hyperledger.besu.datatypes.Address sender = org.hyperledger.besu.datatypes.Address.fromHexString("0x0000000000000000000000000000000000020002");
        Wei amount = Weis.fromSatoshis(Coin.COIN);
        RejectedPegoutReason reason = RejectedPegoutReason.LOW_AMOUNT; // Any reason, just testing the call to the method
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logReleaseBtcRequestRejected(
            sender,
            amount,
            reason
        ));
    }

    @Test
    void logBatchPegoutCreated() {
        Sha256Hash btcTxHash = btcTxMock.getHash();
        List<Hash> rskTxHashes = new ArrayList<>();
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logBatchPegoutCreated(
            btcTxHash,
            rskTxHashes
        ));
    }

    @Test
    void logPegoutConfirmed() {
        Sha256Hash btcTxHash = btcTxMock.getHash();
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logPegoutConfirmed(
            btcTxHash,
            5
        ));
    }

    @Test
    void logPegoutTransactionCreated() {
        Sha256Hash btcTxHash = btcTxMock.getHash();
        List<Coin> outpointValues = Arrays.asList(Coin.COIN, Coin.SATOSHI, Coin.FIFTY_COINS);
        assertThrows(UnsupportedOperationException.class, () -> eventLogger.logPegoutTransactionCreated(
            btcTxHash,
            outpointValues
        ));
    }
}
