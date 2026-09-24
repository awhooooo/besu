package co.rsk.peg.lockingcap;

import static org.junit.jupiter.api.Assertions.*;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.PegTestUtils;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.lockingcap.constants.LockingCapConstants;
import co.rsk.peg.lockingcap.constants.LockingCapMainNetConstants;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(Lifecycle.PER_CLASS)
class LockingCapIT {
    private final LockingCapConstants constants = LockingCapMainNetConstants.getInstance();
    private LockingCapSupport lockingCapSupport;
    private LockingCapStorageProvider lockingCapStorageProvider;
    private StorageAccessor bridgeStorageAccessor;
    private Coin currentLockingCap; // it is used to guarantee the value from getLockingCap() contains the value expected

    @BeforeAll
    void setUp() {
        bridgeStorageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(lockingCapStorageProvider, constants);
    }

    @Test
    @Order(0)
    void getInitialValue_whenFirstTimeGettingLockingCap_shouldReturnInitialValue() {
        // The first time the Locking Cap is requested, it should return the initial value
        Coin expectedLockingCap = constants.getInitialValue();

        // Actual / Assert
        assertLockingCapValue(expectedLockingCap);
    }

    @Test
    @Order(1)
    void increaseLockingCap_whenNewValueIsGreaterThanCurrentLockingCap_shouldSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = currentLockingCap.add(Coin.COIN);

        // Act
        boolean isIncreased = voteToIncreaseLockingCap(newLockingCap);

        // Assert
        assertTrue(isIncreased);
        assertLockingCapValue(newLockingCap);
    }

    @Test
    @Order(2)
    void increaseLockingCap_whenPreviousValueExistsInStorageAndNewLockingCapIsLessThanPreviousValue_shouldNotSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = currentLockingCap.subtract(Coin.COIN);

        // Act
        boolean isIncreased = voteToIncreaseLockingCap(newLockingCap);

        // Assert
        assertFalse(isIncreased);
        assertLockingCapValue(currentLockingCap);
    }

    @Test
    @Order(3)
    void increaseLockingCap_whenPreviousValueExistsInStorageAndNewLockingCapIsGreaterThanPreviousValue_shouldSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = currentLockingCap.add(Coin.COIN);

        // Act
        boolean isIncreased = voteToIncreaseLockingCap(newLockingCap);

        // Assert
        assertTrue(isIncreased);
        assertLockingCapValue(newLockingCap);
    }

    @Test
    @Order(4)
    void increaseLockingCap_whenAnUnauthorizedCallerRequestToIncreaseLockingCapValue_shouldNotSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = currentLockingCap.add(Coin.COIN);
        CallContext tx = PegTestUtils.callFrom(LockingCapCaller.UNAUTHORIZED.getRskAddress());

        // Act
        boolean isIncreased = lockingCapSupport.increaseLockingCap(tx, newLockingCap);

        // Assert
        assertFalse(isIncreased);
        assertLockingCapValue(currentLockingCap);
    }

    @Test
    @Order(5)
    void increaseLockingCap_whenSecondAuthorizedCallerRequestToIncreaseLockingCapValue_shouldSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = currentLockingCap.add(Coin.COIN);

        // Act
        boolean isIncreased = voteToIncreaseLockingCap(newLockingCap, LockingCapCaller.SECOND_AUTHORIZED);

        // Assert
        assertTrue(isIncreased);
        assertLockingCapValue(newLockingCap);
    }

    @Test
    @Order(6)
    void increaseLockingCap_whenThirdAuthorizedCallerRequestToIncreaseLockingCapValue_shouldSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = currentLockingCap.add(Coin.COIN);

        // Act
        boolean isIncreased = voteToIncreaseLockingCap(newLockingCap, LockingCapCaller.THIRD_AUTHORIZED);

        // Assert
        assertTrue(isIncreased);
        assertLockingCapValue(newLockingCap);
    }

    @Test
    @Order(7)
    void increaseLockingCap_whenNewLockingCapIsGreaterThanMaxLockingCap_shouldNotSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin maxLockingCapVoteValueAllowed = currentLockingCap.multiply(constants.getIncrementsMultiplier());
        Coin newLockingCap = maxLockingCapVoteValueAllowed.add(Coin.SATOSHI);

        // Act
        boolean isIncreased = voteToIncreaseLockingCap(newLockingCap);

        // Assert
        assertFalse(isIncreased);
        assertLockingCapValue(currentLockingCap);
    }

    @Test
    @Order(8)
    void increaseLockingCap_whenNewLockingCapIsEqualToMaxLockingCap_shouldSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = currentLockingCap.multiply(constants.getIncrementsMultiplier());

        // Act
        boolean isIncreased = voteToIncreaseLockingCap(newLockingCap);

        // Assert
        assertTrue(isIncreased);
        assertLockingCapValue(newLockingCap);
    }

    @Test
    @Order(9)
    void increaseLockingCap_whenNewLockingCapIsZero_shouldThrowLockingCapIllegalArgumentException() {
        // Arrange
        Coin newLockingCap = Coin.ZERO;

        // Actual / Assert
        assertThrows(
            LockingCapIllegalArgumentException.class,
            () -> voteToIncreaseLockingCap(newLockingCap)
        );
        assertLockingCapValue(currentLockingCap);
    }

    @Test
    @Order(10)
    void increaseLockingCap_whenNewLockingCapIsNegative_shouldThrowLockingCapIllegalArgumentException() {
        // Arrange
        Coin newLockingCap = Coin.NEGATIVE_SATOSHI;

        // Actual / Assert
        assertThrows(
            LockingCapIllegalArgumentException.class,
            () -> voteToIncreaseLockingCap(newLockingCap)
        );
        assertLockingCapValue(currentLockingCap);
    }

    @Test
    @Order(11)
    void increaseLockingCap_whenNewValueIsEqualToCurrentValue_shouldSaveNewLockingCapValue() throws LockingCapIllegalArgumentException {
        // Arrange
        Coin newLockingCap = currentLockingCap;

        // Act
        boolean isIncreased = voteToIncreaseLockingCap(newLockingCap);

        // Assert
        assertTrue(isIncreased);
        assertLockingCapValue(newLockingCap);
    }

    private boolean voteToIncreaseLockingCap(Coin valueToVote) throws LockingCapIllegalArgumentException {
        return voteToIncreaseLockingCap(valueToVote, LockingCapCaller.FIRST_AUTHORIZED);
    }

    private boolean voteToIncreaseLockingCap(Coin valueToVote, LockingCapCaller caller) throws LockingCapIllegalArgumentException {
        CallContext tx = PegTestUtils.callFrom(caller.getRskAddress());

        boolean result = lockingCapSupport.increaseLockingCap(tx, valueToVote);
        lockingCapSupport.save();

        return result;
    }

    private void assertLockingCapValue(Coin expectedLockingCap) {
        // Recreate LockingCapStorageProvider to clear cached value and make sure it is fetched from the storage
        lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);

        // Act
        Optional<Coin> actualLockingCap = lockingCapSupport.getLockingCap();

        // Assert
        assertTrue(actualLockingCap.isPresent());
        assertEquals(expectedLockingCap, actualLockingCap.get());

        // Save the current Locking Cap value to be used in the next test
        currentLockingCap = actualLockingCap.get();
    }
}
