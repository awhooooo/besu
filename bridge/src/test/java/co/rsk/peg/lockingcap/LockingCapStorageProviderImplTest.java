package co.rsk.peg.lockingcap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.lockingcap.constants.LockingCapConstants;
import co.rsk.peg.lockingcap.constants.LockingCapMainNetConstants;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LockingCapStorageProviderImplTest {

    private final LockingCapConstants constants = LockingCapMainNetConstants.getInstance();
    private LockingCapStorageProvider lockingCapStorageProvider;
    private StorageAccessor bridgeStorageAccessor;

    @BeforeEach
    void setUp() {
        bridgeStorageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
    }

    @Test
    void getLockingCap_whenNoPreviousValueExists_shouldReturnOptionalEmpty() {
        // Act
        Optional<Coin> actualLockingCap = lockingCapStorageProvider.getLockingCap();

        // Assert
        assertFalse(actualLockingCap.isPresent());
    }

    @Test
    void getLockingCap_whenLockingCapIsSavedInStorage_shouldReturnSavedValue() {
        // Arrange
        Coin expectedLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        lockingCapStorageProvider.setLockingCap(expectedLockingCap);
        lockingCapStorageProvider.save();
        // Recreate LockingCapStorageProvider to clear cached value and make sure it was saved in the storage
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);

        // Act
        Optional<Coin> actualLockingCap = lockingCapStorageProvider.getLockingCap();

        // Assert
        assertEquals(Optional.of(expectedLockingCap), actualLockingCap);
    }

    @Test
    void setLockingCap_whenNewLockingCapValueIsSet_shouldSetLockingCap() {
        // Arrange
        Coin newLockingCap = constants.getInitialValue().add(Coin.SATOSHI);

        // Act
        lockingCapStorageProvider.setLockingCap(newLockingCap);

        // Assert
        Optional<Coin> actualLockingCap = lockingCapStorageProvider.getLockingCap();
        assertEquals(Optional.of(newLockingCap), actualLockingCap);
    }

    @Test
    void setLockingCap_whenIsSetNullValue_shouldReturnOptionalEmpty() {
        // Act
        lockingCapStorageProvider.setLockingCap(null);

        // Assert
        Optional<Coin> actualLockingCap = lockingCapStorageProvider.getLockingCap();
        assertFalse(actualLockingCap.isPresent());
    }

    @Test
    void save_whenIsSavedANewLockingCapValue_shouldSaveLockingCap() {
        // Arrange
        Coin newLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        lockingCapStorageProvider.setLockingCap(newLockingCap);

        // Act
        lockingCapStorageProvider.save();

        // Assert
        // Recreate LockingCapStorageProvider to clear cached value and make sure it was saved in the storage
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        Optional<Coin> actualLockingCap = lockingCapStorageProvider.getLockingCap();
        assertEquals(Optional.of(newLockingCap), actualLockingCap);
    }
}
