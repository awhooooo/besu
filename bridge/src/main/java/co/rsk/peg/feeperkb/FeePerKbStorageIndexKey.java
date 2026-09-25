package co.rsk.peg.feeperkb;

import co.rsk.peg.utils.StorageKeys;

import org.apache.tuweni.bytes.Bytes32;

public enum FeePerKbStorageIndexKey {
    FEE_PER_KB("feePerKb"),
    FEE_PER_KB_ELECTION("feePerKbElection")
    ;

    private final String key;

    FeePerKbStorageIndexKey(String key) {
        this.key = key;
    }

    public Bytes32 getKey() {
        return StorageKeys.name(key);
    }

}
