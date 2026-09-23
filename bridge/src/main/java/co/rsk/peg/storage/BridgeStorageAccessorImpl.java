package co.rsk.peg.storage;

import co.rsk.peg.host.BridgeHost;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

public class BridgeStorageAccessorImpl implements StorageAccessor {

    private final BridgeHost host;

    public BridgeStorageAccessorImpl(BridgeHost host) {
        this.host = host;
    }

    @Override
    public <T> T getFromRepository(Bytes32 key, RepositoryDeserializer<T> deserializer) {
        byte[] data = host.getStorage(key);
        return deserializer.deserialize(data);
    }

    @Override
    public <T> void saveToRepository(Bytes32 key, T object, RepositorySerializer<T> serializer) {
        byte[] serializedData = getSerializedData(object, serializer);
        saveToRepository(key, serializedData);
    }

    private <T> byte[] getSerializedData(T object, RepositorySerializer<T> serializer) {
        byte[] data = null;
        if (object != null) {
            data = serializer.serialize(object);
        }
        return data;
    }

    @Override
    public void saveToRepository(Bytes32 key, byte[] serializedData) {
        host.putStorage(key, serializedData);
    }

    @Override
    public UInt256 getSlot(UInt256 slot) {
        return host.getSlot(slot);
    }

    @Override
    public void putSlot(UInt256 slot, UInt256 value) {
        host.putSlot(slot, value);
    }
}
