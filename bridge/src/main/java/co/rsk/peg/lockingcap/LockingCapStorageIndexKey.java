package co.rsk.peg.lockingcap;

import co.rsk.peg.utils.StorageKeys;

import org.apache.tuweni.bytes.Bytes32;

public enum LockingCapStorageIndexKey {
    LOCKING_CAP("lockingCap");

    private final String key;

    LockingCapStorageIndexKey(String key) {
        this.key = key;
    }

    public Bytes32 getKey() {
        return StorageKeys.name(key);
    }
}
