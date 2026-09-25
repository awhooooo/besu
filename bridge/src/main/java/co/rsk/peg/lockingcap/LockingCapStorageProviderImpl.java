package co.rsk.peg.lockingcap;

import static co.rsk.peg.lockingcap.LockingCapStorageIndexKey.LOCKING_CAP;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.BridgeSerializationUtils;
import co.rsk.peg.storage.StorageAccessor;
import java.util.Optional;

public class LockingCapStorageProviderImpl implements LockingCapStorageProvider {

    private Coin lockingCap;
    private final StorageAccessor bridgeStorageAccessor;

    public LockingCapStorageProviderImpl(StorageAccessor bridgeStorageAccessor) {
        this.bridgeStorageAccessor = bridgeStorageAccessor;
    }

    @Override
    public Optional<Coin> getLockingCap() {
        if (lockingCap == null) {
            initializeLockingCap();
        }
        return Optional.ofNullable(lockingCap);
    }

    private synchronized void initializeLockingCap() {
        lockingCap = bridgeStorageAccessor.getFromRepository(LOCKING_CAP.getKey(), BridgeSerializationUtils::deserializeCoin);
    }

    @Override
    public void setLockingCap(Coin lockingCap) {
        this.lockingCap = lockingCap;
    }

    @Override
    public void save() {
        Coin currentLockingCap = getLockingCap().orElse(null);
        bridgeStorageAccessor.saveToRepository(
            LOCKING_CAP.getKey(),
            currentLockingCap,
            BridgeSerializationUtils::serializeCoin
        );
    }
}
