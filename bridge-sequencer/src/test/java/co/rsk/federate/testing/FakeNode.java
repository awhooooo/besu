package co.rsk.federate.testing;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import co.rsk.federate.rpc.EthClient;
import co.rsk.federate.rpc.RpcException;
import co.rsk.peg.BridgeMethods;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.Transaction;

/**
 * A node that answers bridge reads from a script and records bridge writes.
 *
 * <p>Everything goes through the real ABI codec in both directions: what a test asserts about a
 * sent transaction is what was actually encoded, decoded back by the same function definition the
 * bridge itself would use. A test that stubbed {@code FederatorSupport} instead would agree with
 * any encoding at all, including a wrong one.
 */
public class FakeNode implements EthClient {

    /** One bridge method sent to the node, with its arguments decoded. */
    public record Sent(BridgeMethods method, Object[] arguments, long nonce) {
    }

    private static final Map<String, BridgeMethods> BY_SELECTOR = bySelector();

    private final Map<BridgeMethods, Function<Object[], Object[]>> answers = new EnumMap<>(BridgeMethods.class);
    private final List<Sent> sent = new ArrayList<>();

    private long blockNumber = 1_000;
    private long pendingNonce;
    private boolean syncing;
    private RuntimeException refuseSends;

    /** Answers this read with these values, encoded as the method declares its outputs. */
    public FakeNode answering(BridgeMethods method, Object... results) {
        answers.put(method, arguments -> results);
        return this;
    }

    /** Answers this read by computing from the call's arguments. */
    public FakeNode answeringWith(BridgeMethods method, Function<Object[], Object[]> answer) {
        answers.put(method, answer);
        return this;
    }

    public FakeNode atHeight(long blockNumber) {
        this.blockNumber = blockNumber;
        return this;
    }

    public FakeNode syncing(boolean syncing) {
        this.syncing = syncing;
        return this;
    }

    public FakeNode refusingSends(RuntimeException failure) {
        this.refuseSends = failure;
        return this;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public List<BridgeMethods> methodsSent() {
        return sent.stream().map(Sent::method).toList();
    }

    public List<Sent> sentOf(BridgeMethods method) {
        return sent.stream().filter(s -> s.method() == method).toList();
    }

    public Optional<Sent> firstOf(BridgeMethods method) {
        return sentOf(method).stream().findFirst();
    }

    public void clearSent() {
        sent.clear();
    }

    @Override
    public Bytes call(Address to, Bytes callData) {
        BridgeMethods method = methodOf(callData);
        Function<Object[], Object[]> answer = answers.get(method);
        if (answer == null) {
            throw new RpcException(-32000, "This test node has no answer for " + method);
        }
        Object[] arguments = method.getFunction().decode(callData);
        return method.getFunction().encodeOutputs(answer.apply(arguments));
    }

    @Override
    public Bytes32Hash sendRawTransaction(Bytes signedTransaction) {
        if (refuseSends != null) {
            throw refuseSends;
        }
        Transaction decoded = Transaction.readFrom(signedTransaction);
        Bytes payload = decoded.getPayload();
        BridgeMethods method = methodOf(payload);
        sent.add(new Sent(method, method.getFunction().decode(payload), decoded.getNonce()));
        pendingNonce = decoded.getNonce() + 1;
        return Bytes32Hash.fromHexString(
            "0x2222222222222222222222222222222222222222222222222222222222222222");
    }

    @Override
    public long blockNumber() {
        return blockNumber;
    }

    @Override
    public long pendingNonce(Address address) {
        return pendingNonce;
    }

    @Override
    public boolean syncing() {
        return syncing;
    }

    @Override
    public Optional<TransactionReceipt> receipt(Bytes32Hash transactionHash) {
        return Optional.empty();
    }

    private static BridgeMethods methodOf(Bytes callData) {
        String selector = callData.slice(0, 4).toHexString();
        BridgeMethods method = BY_SELECTOR.get(selector);
        if (method == null) {
            throw new RpcException(-32000, "No bridge method has selector " + selector);
        }
        return method;
    }

    private static Map<String, BridgeMethods> bySelector() {
        Map<String, BridgeMethods> map = new HashMap<>();
        for (BridgeMethods method : BridgeMethods.values()) {
            map.put(method.getFunction().encodeSignature().toHexString(), method);
        }
        return map;
    }
}
