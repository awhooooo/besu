package co.rsk.test.builders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.rsk.peg.Bridge;
import co.rsk.peg.BridgeSupport;
import co.rsk.peg.BridgeSupportFactory;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.host.CallKind;
import co.rsk.peg.host.InMemoryBridgeHost;
import org.hyperledger.besu.datatypes.Address;

/**
 * Ported from RSKj's test builders. RSKj's contract address, constants, activations, signature cache,
 * transaction, execution block and message type are the bridge constants and the host here: a Bridge is
 * built on the host of one call, so the caller, the call kind and the local call flag are set on the host
 * instead of through an init method. Every RSKIP is active, so there is no activation config to set.
 */
public class BridgeBuilder {
    private BridgeConstants bridgeConstants;
    private BridgeSupport bridgeSupport;
    private final InMemoryBridgeHost host;

    public BridgeBuilder() {
        bridgeConstants = BridgeMainNetConstants.getInstance();
        bridgeSupport = BridgeSupportBuilder.builder().build();
        host = new InMemoryBridgeHost().callKind(CallKind.CALL);
    }

    public BridgeBuilder bridgeConstants(BridgeConstants bridgeConstants) {
        this.bridgeConstants = bridgeConstants;
        return this;
    }

    public BridgeBuilder bridgeSupport(BridgeSupport bridgeSupport) {
        this.bridgeSupport = bridgeSupport;
        return this;
    }

    /** Replaces RSKj's {@code transaction(...)}: the bridge only reads the sender off it. */
    public BridgeBuilder sender(Address sender) {
        host.origin(sender).caller(sender);
        return this;
    }

    /** Replaces RSKj's {@code msgType(...)}. */
    public BridgeBuilder callKind(CallKind callKind) {
        host.callKind(callKind);
        return this;
    }

    public BridgeBuilder localCall(boolean localCall) {
        host.localCall(localCall);
        return this;
    }

    public BridgeBuilder callerIsContract(boolean callerIsContract) {
        host.callerIsContract(callerIsContract);
        return this;
    }

    public BridgeBuilder blockNumber(long blockNumber) {
        host.blockNumber(blockNumber);
        return this;
    }

    public InMemoryBridgeHost host() {
        return host;
    }

    public Bridge build() {
        BridgeSupportFactory bridgeSupportFactory = mock(BridgeSupportFactory.class);
        when(bridgeSupportFactory.newInstance(any())).thenReturn(bridgeSupport);

        return new Bridge(bridgeConstants, bridgeSupportFactory, host);
    }
}
