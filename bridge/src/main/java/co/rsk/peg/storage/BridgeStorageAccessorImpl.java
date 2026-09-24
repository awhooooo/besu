package co.rsk.peg.storage;

import co.rsk.peg.host.BridgeHost;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * <p>One accessor serves one bridge call: every provider the support factory builds shares it, so it sees every
 * read and every write of a bridge storage key for the life of that call. That lets it remember what a key held
 * and skip writing a value back unchanged.
 *
 * <p>The saving is in reads, not writes. A value of arbitrary length is spread over slots, and writing one
 * compares each slot before writing it, so an unchanged value already wrote nothing, but it read every slot it
 * might have written to find that out. A provider saves everything it loaded, whether or not the call touched
 * it, so that read was the common case: a call that only inspects a list paid for it twice.
 */
public class BridgeStorageAccessorImpl implements StorageAccessor {

    private final BridgeHost host;

    /**
     * What each key held when this accessor last read or wrote it. Absent means this accessor has not touched
     * the key, so nothing can be assumed about it and the write goes through.
     */
    private final Map<Bytes32, byte[]> known = new HashMap<>();

    public BridgeStorageAccessorImpl(BridgeHost host) {
        this.host = host;
    }

    @Override
    public <T> T getFromRepository(Bytes32 key, RepositoryDeserializer<T> deserializer) {
        byte[] data = host.getStorage(key);
        known.put(key, data);
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
        if (known.containsKey(key) && sameValue(known.get(key), serializedData)) {
            return;
        }
        host.putStorage(key, serializedData);
        known.put(key, serializedData);
    }

    /** A null value and an empty one both mean the key holds nothing, which is how the host stores them. */
    private static boolean sameValue(byte[] one, byte[] other) {
        if (one == null || one.length == 0) {
            return other == null || other.length == 0;
        }
        return Arrays.equals(one, other);
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
