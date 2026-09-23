package co.rsk.peg.storage;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

public interface StorageAccessor {

    <T> T getFromRepository(Bytes32 key, RepositoryDeserializer<T> deserializer);

    <T> void saveToRepository(Bytes32 key, T value, RepositorySerializer<T> serializer);

    void saveToRepository(Bytes32 key, byte[] data);

    /** A raw 32-byte slot of the bridge account, zero when unset. Record layouts address slots directly. */
    UInt256 getSlot(UInt256 slot);

    /** Writes a raw slot; an unchanged value is not written. */
    void putSlot(UInt256 slot, UInt256 value);

    interface RepositoryDeserializer<T> {
        T deserialize(byte[] value);
    }

    interface RepositorySerializer<T> {
        byte[] serialize(T value);
    }
}
