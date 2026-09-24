package co.rsk.peg.lockingcap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.PegTestUtils;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.lockingcap.constants.LockingCapConstants;
import co.rsk.peg.lockingcap.constants.LockingCapMainNetConstants;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LockingCapSupportImplTest {
    private final LockingCapConstants constants = LockingCapMainNetConstants.getInstance();
    private LockingCapSupport lockingCapSupport;
    private LockingCapStorageProvider lockingCapStorageProvider;
    private StorageAccessor bridgeStorageAccessor;

    @BeforeEach
    void setUp() {
        bridgeStorageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(lockingCapStorageProvider, constants);
    }

    @Test
    void getLockingCap_whenNoValueExistsInStorage_shouldReturnInitialValue() {
        // Arrange
        Optional<Coin> expectedLockingCap = Optional.of(constants.getInitialValue());

        // Act
        Optional<Coin> actualLockingCap = lockingCapSupport.getLockingCap();

        // Assert
        assertEquals(expectedLockingCap, actualLockingCap);
    }

    @Test
    void getLockingCap_whenLockingCapValueIsSavedInStorage_shouldReturnSavedValue() {
        // Arrange
        Coin expectedLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        lockingCapStorageProvider.setLockingCap(expectedLockingCap);
        lockingCapSupport.save();
        // Recreate LockingCapSupport to load the previous value from storage
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(lockingCapStorageProvider, constants);

        // Act
        Optional<Coin> actualLockingCap = lockingCapSupport.getLockingCap();

        // Assert
        assertEquals(Optional.of(expectedLockingCap), actualLockingCap);
    }

    @Test
    void increaseLockingCap_whenNewValueIsGreaterThanCurrentLockingCap_shouldReturnTrue()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act
        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertTrue(actualResult);
        assertEquals(Optional.of(newLockingCap), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenNewValueIsLessThanInitialValue_shouldReturnFalse()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = constants.getInitialValue().subtract(Coin.SATOSHI);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act
        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertFalse(actualResult);
        assertEquals(Optional.of(constants.getInitialValue()), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenPreviousValueExistsInStorageAndNewLockingCapIsGreaterThanPreviousValue_shouldReturnTrue()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin previousLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        lockingCapStorageProvider.setLockingCap(previousLockingCap);
        lockingCapSupport.save();
        // Recreate LockingCapSupport to load the previous value from storage
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(lockingCapStorageProvider, constants);

        Coin newLockingCap = previousLockingCap.add(Coin.SATOSHI);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act
        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertTrue(actualResult);
        assertEquals(Optional.of(newLockingCap), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenPreviousValueExistsInStorageAndNewLockingCapIsLessThanPreviousValue_shouldReturnFalse()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin expectedLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        lockingCapStorageProvider.setLockingCap(expectedLockingCap);
        lockingCapSupport.save();
        // Recreate LockingCapSupport to load the previous value from storage
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(lockingCapStorageProvider, constants);

        Coin newLockingCap = expectedLockingCap.subtract(Coin.SATOSHI);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act
        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertFalse(actualResult);
        assertEquals(Optional.of(expectedLockingCap), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenNewLockingCapIsGreaterThanTwentyOneMillionBtc_shouldReturnFalse()
        throws LockingCapIllegalArgumentException {
        Coin newLockingCapTooLarge = mock(Coin.class);
        when(newLockingCapTooLarge.getValue()).thenReturn(NetworkParameters.MAX_MONEY.getValue() + 1L);
        when(newLockingCapTooLarge.compareTo(eq(NetworkParameters.MAX_MONEY))).thenReturn(1);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCapTooLarge);

        assertFalse(actualResult);
        assertEquals(Optional.of(constants.getInitialValue()), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenAnUnauthorizedCallerRequestToIncreaseLockingCapValue_shouldReturnFalse()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.UNAUTHORIZED.getRskAddress());

        // Act
        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertFalse(actualResult);
        assertEquals(Optional.of(constants.getInitialValue()), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenNewLockingCapIsGreaterThanMaxLockingCap_shouldReturnFalse()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin expectedLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        lockingCapStorageProvider.setLockingCap(expectedLockingCap);
        lockingCapSupport.save();
        // Recreate LockingCapSupport to load the previous value from storage
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(lockingCapStorageProvider, constants);

        Coin maxLockingCapVoteValueAllowed = expectedLockingCap.multiply(constants.getIncrementsMultiplier());
        Coin newLockingCap = maxLockingCapVoteValueAllowed.add(Coin.SATOSHI);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act
        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertFalse(actualResult);
        assertEquals(Optional.of(expectedLockingCap), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenNewLockingCapIsEqualToMaxLockingCap_shouldReturnTrue()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin previousLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        lockingCapStorageProvider.setLockingCap(previousLockingCap);
        lockingCapSupport.save();
        // Recreate LockingCapSupport to load the previous value from storage
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(lockingCapStorageProvider, constants);

        // The new locking cap is the maximum value that can be set
        Coin newLockingCap = previousLockingCap.multiply(constants.getIncrementsMultiplier());
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act
        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertTrue(actualResult);
        assertEquals(Optional.of(newLockingCap), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenNewValueIsEqualToCurrentValue_shouldReturnTrue()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = constants.getInitialValue();
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act
        boolean actualResult = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertTrue(actualResult);
        assertEquals(Optional.of(constants.getInitialValue()), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenNewLockingCapIsZero_shouldReturnFalse() {
        // Arrange
        Coin newLockingCap = Coin.ZERO;
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act / Assert
        assertThrows(LockingCapIllegalArgumentException.class, () -> lockingCapSupport.increaseLockingCap(tx, newLockingCap));
        assertEquals(Optional.of(constants.getInitialValue()), lockingCapSupport.getLockingCap());
    }

    @Test
    void increaseLockingCap_whenNewLockingCapIsNegative_shouldReturnFalse() {
        // Arrange
        Coin newLockingCap = Coin.NEGATIVE_SATOSHI;
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());

        // Act / Assert
        assertThrows(LockingCapIllegalArgumentException.class, () -> lockingCapSupport.increaseLockingCap(tx, newLockingCap));
        assertEquals(Optional.of(constants.getInitialValue()), lockingCapSupport.getLockingCap());
    }

    @Test
    void save_whenIsIncreasedLockingCapValue_shouldSaveLockingCap()
        throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());
        lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Act
        lockingCapSupport.save();

        // Assert
        // Recreate LockingCapSupport to load the previous value from storage and make sure it was saved
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(lockingCapStorageProvider, constants);
        assertEquals(Optional.of(newLockingCap), lockingCapSupport.getLockingCap());
    }
}
