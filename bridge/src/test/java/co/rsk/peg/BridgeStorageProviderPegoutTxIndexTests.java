package co.rsk.peg;

import static co.rsk.peg.BridgeStorageIndexKey.PEGOUT_TX_SIG_HASH;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import co.rsk.peg.host.BridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import org.apache.tuweni.bytes.Bytes32;
import org.mockito.Mockito;

class BridgeStorageProviderPegoutTxIndexTests {
    private static final String DUPLICATED_INSERTION_ERROR_MESSAGE = "Given pegout tx sigHash %s already exists in the index. Index entries are considered unique.";
    private static final byte TRUE_VALUE = (byte) 1;

    private final BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
    private final NetworkParameters mainnetBtcParams = bridgeMainnetConstants.getBtcParams();

    private static Stream<Arguments> invalid_entry_values() {
        return Stream.of(
            Arguments.of(new byte[]{1, 2}),
            Arguments.of(new byte[]{0})
        );
    }

    private static Stream<Arguments> valid_sigHash_parameters() {
        return Stream.of(
            Arguments.of(Sha256Hash.ZERO_HASH),
            Arguments.of(BitcoinTestUtils.createHash(10)),
            Arguments.of(BitcoinTestUtils.createHash(20))
        );
    }

    @Test
    void hasPegoutSigHash_null_sigHash() {
        // Arrange

        BridgeHost host = mock(BridgeHost.class);

        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);

        // Act
        boolean result = bridgeStorageProvider.hasPegoutTxSigHash(null);

        // Assert
        Assertions.assertFalse(result);

        verify(host, never()).getStorage(any());

        verify(host, never()).putStorage(any(), any());
    }

    @Test
    void hasPegoutTxSigHash_null_stored_value() {
        // Arrange

        BridgeHost host = mock(BridgeHost.class);

        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);
        Sha256Hash sigHash = BitcoinTestUtils.createHash(5);
        Bytes32 entryKey = PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString());
        when(host.getStorage(entryKey)).thenReturn(null);

        // Act
        boolean result = bridgeStorageProvider.hasPegoutTxSigHash(sigHash);

        // Assert
        Assertions.assertFalse(result);

        verify(host, times(1)).getStorage(entryKey);

        verify(host, never()).putStorage(any(), any());
    }

    @ParameterizedTest
    @MethodSource("invalid_entry_values")
    void hasPegoutTxSigHash_invalid_stored_data(byte[] invalidStoredValue) {
        // Arrange

        BridgeHost host = mock(BridgeHost.class);

        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);
        Sha256Hash sigHash = BitcoinTestUtils.createHash(5);
        Bytes32 entryKey = PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString());
        when(host.getStorage(entryKey)).thenReturn(invalidStoredValue);

        // Act
        boolean result = bridgeStorageProvider.hasPegoutTxSigHash(sigHash);

        // Assert
        Assertions.assertFalse(result);

        verify(host, times(1)).getStorage(entryKey);

        verify(host, never()).putStorage(any(), any());
    }

    @ParameterizedTest
    @MethodSource("valid_sigHash_parameters")
    void hasPegoutTxSigHash_non_null_sigHash(Sha256Hash sigHash) {
        // Arrange

        BridgeHost host = mock(BridgeHost.class);
        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);

        // Act
        boolean result = bridgeStorageProvider.hasPegoutTxSigHash(sigHash);

        // Assert
        Assertions.assertFalse(result);
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));

        verify(host, never()).putStorage(any(), any());
    }

    @Test
    void hasPegoutTxSigHash_passing_existing_sigHash() {
        // Arrange

        Sha256Hash sigHash = BitcoinTestUtils.createHash(15);

        BridgeHost host = mock(BridgeHost.class);
        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);

        // Check if sigHash exists when there are no entries in the index
        boolean result = bridgeStorageProvider.hasPegoutTxSigHash(sigHash);
        Assertions.assertFalse(result);

        // Verify the method check if the given sigHash exists in the index
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));

        // Verify sigHash is not persisted into the index when calling hasPegoutTxSigHash
        verify(host, never()).putStorage(any(), any());
        // reset calls counter
        Mockito.reset(host);

        // Let's set the sigHash and then call hasPegoutTxSigHash, it should return false.
        bridgeStorageProvider.setPegoutTxSigHash(sigHash);
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));
        // reset calls counter
        Mockito.reset(host);

        boolean sigHashShouldNotExist = bridgeStorageProvider.hasPegoutTxSigHash(sigHash);
        Assertions.assertFalse(sigHashShouldNotExist);
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));
        Mockito.reset(host);

        // Let's save pending sigHash into the host
        bridgeStorageProvider.save();
        verify(host, times(1)).putStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()), new byte[]{(byte) 1});
        verify(host, never()).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));
        Mockito.reset(host);

        // Let's create a stub for the just saved sigHash
        when(host.getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString())))
            .thenReturn(new byte[]{TRUE_VALUE});

        // Check if saved sigHash exists
        boolean shouldFindSigHash = bridgeStorageProvider.hasPegoutTxSigHash(sigHash);
        Assertions.assertTrue(shouldFindSigHash);
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));
    }

    @Test
    void setPegoutTxSigHash_null() {
        // Arrange

        BridgeHost host = mock(BridgeHost.class);

        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);

        // Act
        bridgeStorageProvider.setPegoutTxSigHash(null);
        bridgeStorageProvider.savePegoutTxSigHashes();

        // Assert
        verify(host, never()).getStorage(any());

        verify(host, never()).putStorage(any(), any());
    }

    @ParameterizedTest
    @MethodSource("valid_sigHash_parameters")
    void setPegoutTxSigHash_non_null(Sha256Hash sigHash) {
        // Arrange

        BridgeHost host = mock(BridgeHost.class);
        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);

        // Act
        bridgeStorageProvider.setPegoutTxSigHash(sigHash);
        bridgeStorageProvider.savePegoutTxSigHashes();

        // Assert
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));

        verify(host, times(1)).putStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()), new byte[]{(byte) 1});
    }

    @Test
    void setPegoutTxSigHash_passing_existing() {
        // Arrange

        Sha256Hash sigHash = BitcoinTestUtils.createHash(15);

        BridgeHost host = mock(BridgeHost.class);
        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);

        // Add a sigHash when index is empty
        bridgeStorageProvider.setPegoutTxSigHash(sigHash);

        // Verify the method check if the given sigHash already exists in the index
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));
        // Verify sigHash is not persisted into the index when save has not been called.
        verify(host, never()).putStorage(any(), any());
        Mockito.reset(host);

        // Try to set same sigHash, it should allow it to do it.
        bridgeStorageProvider.setPegoutTxSigHash(sigHash);
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));

        // Try to set a different sigHash. It should allow it as well.
        Sha256Hash newSigHash = BitcoinTestUtils.createHash(7);
        bridgeStorageProvider.setPegoutTxSigHash(newSigHash);

        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", newSigHash.toString()));
        // Verify no sigHash is persisted yet
        verify(host, never()).putStorage(any(), any());
        Mockito.reset(host);

        // Now let's persist the pending to save sigHash
        bridgeStorageProvider.save();

        // Check the persisted sigHash is the newSigHash
        verify(host, times(1)).putStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", newSigHash.toString()),
            new byte[]{TRUE_VALUE}
        );
        when(host.getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", newSigHash.toString())))
            .thenReturn(new byte[]{TRUE_VALUE});

        // Try to set again the new sigHash that was persisted into the host
        assertThrows(
            IllegalStateException.class,
            () -> bridgeStorageProvider.setPegoutTxSigHash(newSigHash),
            String.format(DUPLICATED_INSERTION_ERROR_MESSAGE, newSigHash)
        );
    }

    @Test
    void setPegoutTxSigHash_multiple_sigHash_in_a_single_rsk_tx() {
        // Arrange

        Sha256Hash sigHash = BitcoinTestUtils.createHash(15);
        Sha256Hash sigHash2 = BitcoinTestUtils.createHash(25);
        Sha256Hash sigHash3 = BitcoinTestUtils.createHash(35);

        BridgeHost host = mock(BridgeHost.class);
        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host);

        // Set multiple sighash when index is empty
        bridgeStorageProvider.setPegoutTxSigHash(sigHash);
        bridgeStorageProvider.setPegoutTxSigHash(sigHash2);
        bridgeStorageProvider.setPegoutTxSigHash(sigHash3);

        // Verify the method check if the given sigHash already exists in the index
        verify(host, times(3)).getStorage(any());
        // Verify sigHash is not persisted into the index when save has not been called.
        verify(host, never()).putStorage(any(), any());
        reset(host);

        // Try to set same sigHash, it should allow it to do it.
        bridgeStorageProvider.setPegoutTxSigHash(sigHash);
        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString()));

        // Try to set a different sigHash. It should allow it as well.
        Sha256Hash sigHash4 = BitcoinTestUtils.createHash(7);
        bridgeStorageProvider.setPegoutTxSigHash(sigHash4);

        verify(host, times(1)).getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash4.toString()));
        // Verify no sigHash is persisted yet
        verify(host, never()).putStorage(any(), any());
        reset(host);

        // Now let's persist pending sigHashes
        bridgeStorageProvider.save();

        // Check the persisted sigHash is the sigHash4
        verify(host, times(4)).putStorage(any(), any());
        when(host.getStorage(PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash4.toString())))
            .thenReturn(new byte[]{TRUE_VALUE});

        // Try to set again the new sigHash that was persisted into the host
        assertThrows(
            IllegalStateException.class,
            () -> bridgeStorageProvider.setPegoutTxSigHash(sigHash4),
            String.format(DUPLICATED_INSERTION_ERROR_MESSAGE, sigHash4)
        );
    }

    private BridgeStorageProvider createBridgeStorageProvider(BridgeHost host) {
        return new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), mainnetBtcParams);
    }
}
