package co.rsk.peg.host;

import co.rsk.peg.Bridge;
import co.rsk.peg.BridgeSupportFactory;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.exception.VMException;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/**
 * The bridge as a Besu precompiled contract.
 *
 * <p>Besu prices a precompile from its input alone and charges that price before calling in. The bridge
 * prices one method from state (the depth of a Bitcoin block in its header chain), so this contract reports
 * no requirement up front and runs RSKj's own sequence inside {@link #computePrecompile}: price the call
 * with a {@link Bridge} built on the frame, halt when the frame cannot pay, charge, execute.
 *
 * <p>The outcomes are those of RSKj's {@code TransactionExecutor.call} and
 * {@code Program.callToPrecompiledAddress}. Too little gas consumes all of it. A failing execution reverts:
 * the required gas stays spent, the rest returns to the caller and the state changes roll back. A successful
 * one returns the ABI-encoded result, empty for a void method. A frame without the context the bridge needs
 * (see {@link FrameBridgeHost}) fails before any of this, as it must.
 */
public final class BridgePrecompiledContract implements PrecompiledContract {

    private final BridgeConstants bridgeConstants;
    private final BridgeSupportFactory bridgeSupportFactory;

    public BridgePrecompiledContract(BridgeConstants bridgeConstants, BridgeSupportFactory bridgeSupportFactory) {
        this.bridgeConstants = bridgeConstants;
        this.bridgeSupportFactory = bridgeSupportFactory;
    }

    @Override
    public String getName() {
        return "Bridge";
    }

    /** Zero: the bridge's price needs state, so {@link #computePrecompile} computes and charges it. */
    @Override
    public long gasRequirement(Bytes input) {
        return 0;
    }

    @Override
    public PrecompileContractResult computePrecompile(Bytes input, MessageFrame frame) {
        Bridge bridge = new Bridge(bridgeConstants, bridgeSupportFactory, new FrameBridgeHost(frame));

        long requiredGas = bridge.getGasForData(input);
        if (frame.getRemainingGas() < requiredGas) {
            return PrecompileContractResult.halt(Bytes.EMPTY, Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));
        }
        frame.decrementRemainingGas(requiredGas);

        try {
            return PrecompileContractResult.success(bridge.execute(input));
        } catch (VMException e) {
            return PrecompileContractResult.revert(Bytes.EMPTY);
        }
    }
}
