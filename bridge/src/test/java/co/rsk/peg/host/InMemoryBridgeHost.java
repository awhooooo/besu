package co.rsk.peg.host;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;

/** A host backed by maps, for tests. Every context field is settable; defaults are a plain external call. */
public final class InMemoryBridgeHost implements BridgeHost {

    private final Map<Bytes32, byte[]> storage = new HashMap<>();
    private final Map<Address, Wei> balances = new HashMap<>();
    private final List<Log> logs = new ArrayList<>();

    private long blockNumber = 1;
    private long blockTimestamp = 1_700_000_000L;
    private Address origin = Address.fromHexString("0x0000000000000000000000000000000000000001");
    private Address caller = origin;
    private Hash transactionHash = Hash.fromHexString("0x1111111111111111111111111111111111111111111111111111111111111111");
    private Wei callValue = Wei.ZERO;
    private CallKind callKind = CallKind.CALL;
    private boolean localCall = false;
    private boolean callerIsContract = false;
    private byte[] originPublicKey;

    public InMemoryBridgeHost blockNumber(long value) { this.blockNumber = value; return this; }
    public InMemoryBridgeHost blockTimestamp(long value) { this.blockTimestamp = value; return this; }
    public InMemoryBridgeHost origin(Address value) { this.origin = value; return this; }
    public InMemoryBridgeHost caller(Address value) { this.caller = value; return this; }
    public InMemoryBridgeHost transactionHash(Hash value) { this.transactionHash = value; return this; }
    public InMemoryBridgeHost callValue(Wei value) { this.callValue = value; return this; }
    public InMemoryBridgeHost callKind(CallKind value) { this.callKind = value; return this; }
    public InMemoryBridgeHost localCall(boolean value) { this.localCall = value; return this; }
    public InMemoryBridgeHost callerIsContract(boolean value) { this.callerIsContract = value; return this; }
    public InMemoryBridgeHost originPublicKey(byte[] value) { this.originPublicKey = value == null ? null : value.clone(); return this; }
    public InMemoryBridgeHost balance(Address account, Wei value) { balances.put(account, value); return this; }

    public List<Log> logs() { return Collections.unmodifiableList(logs); }
    public int storedEntries() { return storage.size(); }

    @Override public long blockNumber() { return blockNumber; }
    @Override public long blockTimestamp() { return blockTimestamp; }
    @Override public Address origin() { return origin; }
    @Override public Address caller() { return caller; }
    @Override public Hash transactionHash() { return transactionHash; }
    @Override public Wei callValue() { return callValue; }
    @Override public CallKind callKind() { return callKind; }
    @Override public boolean isLocalCall() { return localCall; }
    @Override public boolean callerIsContract() { return callerIsContract; }
    @Override public Optional<byte[]> originPublicKey() { return Optional.ofNullable(originPublicKey).map(byte[]::clone); }

    @Override
    public byte[] getStorage(Bytes32 key) {
        byte[] value = storage.get(key);
        return value == null ? null : value.clone();
    }

    @Override
    public void putStorage(Bytes32 key, byte[] value) {
        if (value == null || value.length == 0) {
            storage.remove(key);
        } else {
            storage.put(key, value.clone());
        }
    }

    @Override
    public Wei balanceOf(Address account) {
        return balances.getOrDefault(account, Wei.ZERO);
    }

    @Override
    public void transfer(Address from, Address to, Wei amount) {
        Wei available = balanceOf(from);
        if (available.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance in " + from + ": " + available + " < " + amount);
        }
        balances.put(from, available.subtract(amount));
        balances.put(to, balanceOf(to).add(amount));
    }

    @Override
    public void emitLog(Log log) {
        logs.add(log);
    }
}
