package co.rsk.peg;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.exception.VMException;
import co.rsk.test.builders.BridgeBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Random;

class BridgeRSKIP220NewMethodsTest {
    private static final Random random = new Random(BridgeRSKIP220NewMethodsTest.class.hashCode());

    private BridgeSupport bridgeSupport;
    private Bridge bridge;

    @BeforeEach
    void beforeEach() {
        bridgeSupport = mock(BridgeSupport.class);

        bridge = new BridgeBuilder()
            .bridgeConstants(new BridgeRegTestConstants())
            .bridgeSupport(bridgeSupport)
            .blockNumber(42L)
            .build();
    }

    @Test
    void getBtcBlockchainBestBlockHeader() throws IOException, BlockStoreException {
        byte[] header = new byte[80];
        random.nextBytes(header);
        when(bridgeSupport.getBtcBlockchainBestBlockHeader()).thenReturn(header);
        byte[] result = bridge.getBtcBlockchainBestBlockHeader(new Object[0]);

        Assertions.assertArrayEquals(header, result);
    }

    @Test
    void getBtcBlockchainBestChainHeight() throws IOException, BlockStoreException, VMException {
        when(bridgeSupport.getBtcBlockchainBestChainHeight()).thenReturn(42);

        int result = bridge.getBtcBlockchainBestChainHeight(new Object[0]);

        Assertions.assertEquals(42, result);
    }

    @Test
    void getBtcBlockchainBlockHeaderByHash() throws IOException, BlockStoreException {
        byte[] hashBytes = new byte[32];
        random.nextBytes(hashBytes);
        byte[] header = new byte[80];
        random.nextBytes(header);

        when(bridgeSupport.getBtcBlockchainBlockHeaderByHash(Sha256Hash.wrap(hashBytes))).thenReturn(header);
        byte[] result = bridge.getBtcBlockchainBlockHeaderByHash(new Object[] { hashBytes });

        Assertions.assertArrayEquals(header, result);
    }

    @Test
    void getBtcBlockchainBlockHeaderByHeight() throws IOException, BlockStoreException {
        byte[] header = new byte[80];
        random.nextBytes(header);
        BigInteger height = BigInteger.TEN;

        when(bridgeSupport.getBtcBlockchainBlockHeaderByHeight(10)).thenReturn(header);
        byte[] result = bridge.getBtcBlockchainBlockHeaderByHeight(new Object[] { height });

        Assertions.assertArrayEquals(header, result);
    }

    @Test
    void getBtcBlockchainParentBlockHeaderByHash() throws IOException, BlockStoreException {
        byte[] hashBytes = new byte[32];
        random.nextBytes(hashBytes);
        byte[] header = new byte[80];
        random.nextBytes(header);

        when(bridgeSupport.getBtcBlockchainParentBlockHeaderByHash(Sha256Hash.wrap(hashBytes))).thenReturn(header);
        byte[] result = bridge.getBtcBlockchainParentBlockHeaderByHash(new Object[] { hashBytes });

        Assertions.assertArrayEquals(header, result);
    }
}
