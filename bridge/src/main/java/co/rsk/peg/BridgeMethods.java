/*
 * This file is part of RskJ
 * Copyright (C) 2018 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.peg;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import co.rsk.peg.abi.AbiFunction;
import org.apache.tuweni.bytes.Bytes;
import co.rsk.peg.host.CallKind;

/**
 * Represents the methods of the Bridge contract, encapsulating details such as 
 * the Application Binary Interface (ABI), execution costs, and method implementations.
 *
 * Each enum constant corresponds to a specific method of the Bridge contract, 
 * defining its signature and providing the necessary information for execution.
 */
public enum BridgeMethods {
    ADD_FEDERATOR_PUBLIC_KEY_MULTIKEY(
        AbiFunction.fromSignature(
            "addFederatorPublicKeyMultikey",
            new String[]{"bytes", "bytes", "bytes"},
            new String[]{"int256"}
        ),
        fixedCost(13000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::addFederatorPublicKeyMultikey,
        fixedPermission(false)
    ),
    ADD_SIGNATURE(
        AbiFunction.fromSignature(
            "addSignature",
            new String[]{"bytes", "bytes[]", "bytes"},
            new String[]{}
        ),
        fixedCost(70000L),
        Bridge.activeRetiringAndProposedFederationOnly((BridgeMethodExecutorVoid) Bridge::addSignature, "addSignature"),
        fixedPermission(false)
    ),
    COMMIT_FEDERATION(
        AbiFunction.fromSignature(
            "commitFederation",
            new String[]{"bytes"},
            new String[]{"int256"}
        ),
        fixedCost(38000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::commitFederation,
        fixedPermission(false)
    ),
    CREATE_FEDERATION(
        AbiFunction.fromSignature(
            "createFederation",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(11000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::createFederation,
        fixedPermission(false)
    ),
    GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT(
        AbiFunction.fromSignature(
            "getBtcBlockchainBestChainHeight",
            new String[]{},
            new String[]{"int"}
        ),
        fixedCost(19000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getBtcBlockchainBestChainHeight,
        fromMethod(Bridge::getBtcBlockchainBestChainHeightOnlyAllowsLocalCalls),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_BTC_BLOCKCHAIN_INITIAL_BLOCK_HEIGHT(
        AbiFunction.fromSignature(
            "getBtcBlockchainInitialBlockHeight",
            new String[]{},
            new String[]{"int"}
        ),
        fixedCost(20000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getBtcBlockchainInitialBlockHeight,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_BTC_BLOCKCHAIN_BLOCK_HASH_AT_DEPTH(
        AbiFunction.fromSignature(
            "getBtcBlockchainBlockHashAtDepth",
            new String[]{"int256"},
            new String[]{"bytes"}
        ),
        fixedCost(20000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getBtcBlockchainBlockHashAtDepth,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_BTC_TRANSACTION_CONFIRMATIONS(
        AbiFunction.fromSignature(
            "getBtcTransactionConfirmations",
            new String[]{"bytes32", "bytes32", "uint256", "bytes32[]"},
            new String[]{"int256"}
        ),
        fromMethod(Bridge::getBtcTransactionConfirmationsGetCost),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getBtcTransactionConfirmations,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_BTC_TX_HASH_PROCESSED_HEIGHT(
        AbiFunction.fromSignature(
            "getBtcTxHashProcessedHeight",
            new String[]{"string"},
            new String[]{"int64"}
        ),
        fixedCost(22000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getBtcTxHashProcessedHeight,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_FEDERATION_ADDRESS(
        AbiFunction.fromSignature(
            "getFederationAddress",
            new String[]{},
            new String[]{"string"}
        ),
        fixedCost(11000L),
        (BridgeMethodExecutorTyped<String>) Bridge::getFederationAddress,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_FEDERATION_CREATION_BLOCK_NUMBER(
        AbiFunction.fromSignature(
            "getFederationCreationBlockNumber",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(10000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getFederationCreationBlockNumber,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_FEDERATION_CREATION_TIME(
        AbiFunction.fromSignature(
            "getFederationCreationTime",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(10000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getFederationCreationTime,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_FEDERATION_SIZE(
        AbiFunction.fromSignature(
            "getFederationSize",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(10000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getFederationSize,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_FEDERATION_THRESHOLD(
        AbiFunction.fromSignature(
            "getFederationThreshold",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(11000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getFederationThreshold,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_FEDERATOR_PUBLIC_KEY_OF_TYPE(
        AbiFunction.fromSignature(
            "getFederatorPublicKeyOfType",
            new String[]{"int256", "string"},
            new String[]{"bytes"}
        ),
        fixedCost(10000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getFederatorPublicKeyOfType,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_FEE_PER_KB(
        AbiFunction.fromSignature(
            "getFeePerKb",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(2000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getFeePerKb,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_MINIMUM_LOCK_TX_VALUE(
        AbiFunction.fromSignature(
            "getMinimumLockTxValue",
            new String[]{},
            new String[]{"int"}
        ),
        fixedCost(2000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getMinimumLockTxValue,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_PENDING_FEDERATION_HASH(
        AbiFunction.fromSignature(
            "getPendingFederationHash",
            new String[]{},
            new String[]{"bytes"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getPendingFederationHashSerialized,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_PENDING_FEDERATION_SIZE(
        AbiFunction.fromSignature(
            "getPendingFederationSize",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getPendingFederationSize,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_PENDING_FEDERATOR_PUBLIC_KEY_OF_TYPE(
        AbiFunction.fromSignature(
            "getPendingFederatorPublicKeyOfType",
            new String[]{"int256", "string"},
            new String[]{"bytes"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getPendingFederatorPublicKeyOfType,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_RETIRING_FEDERATION_ADDRESS(
        AbiFunction.fromSignature(
            "getRetiringFederationAddress",
            new String[]{},
            new String[]{"string"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<String>) Bridge::getRetiringFederationAddress,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_RETIRING_FEDERATION_CREATION_BLOCK_NUMBER(
        AbiFunction.fromSignature(
            "getRetiringFederationCreationBlockNumber",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getRetiringFederationCreationBlockNumber,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_RETIRING_FEDERATION_CREATION_TIME(
        AbiFunction.fromSignature(
            "getRetiringFederationCreationTime",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getRetiringFederationCreationTime,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_RETIRING_FEDERATION_SIZE(
        AbiFunction.fromSignature(
            "getRetiringFederationSize",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getRetiringFederationSize,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_RETIRING_FEDERATION_THRESHOLD(
        AbiFunction.fromSignature(
            "getRetiringFederationThreshold",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getRetiringFederationThreshold,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE(
        AbiFunction.fromSignature(
            "getRetiringFederatorPublicKeyOfType",
            new String[]{"int256", "string"},
            new String[]{"bytes"}
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getRetiringFederatorPublicKeyOfType,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_PROPOSED_FEDERATION_ADDRESS(
        AbiFunction.fromSignature(
            "getProposedFederationAddress",
            new String[]{},
            new String[]{ "string" }
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<String>) Bridge::getProposedFederationAddress,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_PROPOSED_FEDERATION_SIZE(
        AbiFunction.fromSignature(
                "getProposedFederationSize",
                new String[]{},
                new String[]{ "int256" }
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getProposedFederationSize,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_PROPOSED_FEDERATION_CREATION_TIME(
        AbiFunction.fromSignature(
            "getProposedFederationCreationTime",
            new String[]{},
            new String[]{ "int256" }
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getProposedFederationCreationTime,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_PROPOSED_FEDERATION_CREATION_BLOCK_NUMBER(
        AbiFunction.fromSignature(
            "getProposedFederationCreationBlockNumber",
            new String[]{},
            new String[]{ "int256" }
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getProposedFederationCreationBlockNumber,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_PROPOSED_FEDERATOR_PUBLIC_KEY_OF_TYPE(
        AbiFunction.fromSignature(
                "getProposedFederatorPublicKeyOfType",
                new String[]{ "int256", "string" },
                new String[]{ "bytes" }
        ),
        fixedCost(3000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getProposedFederatorPublicKeyOfType,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_STATE_FOR_BTC_RELEASE_CLIENT(
        AbiFunction.fromSignature(
            "getStateForBtcReleaseClient",
            new String[]{},
            new String[]{"bytes"}
        ),
        fixedCost(4000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getStateForBtcReleaseClient,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_STATE_FOR_SVP_CLIENT(
        AbiFunction.fromSignature(
            "getStateForSvpClient",
            new String[]{},
            new String[]{"bytes"}
        ),
        fixedCost(4000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getStateForSvpClient,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_STATE_FOR_DEBUGGING(
        AbiFunction.fromSignature(
            "getStateForDebugging",
            new String[]{},
            new String[]{"bytes"}
        ),
        fixedCost(3_000_000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getStateForDebugging,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_LOCKING_CAP(
        AbiFunction.fromSignature(
            "getLockingCap",
            new String[]{},
            new String[]{"int256"}
        ),
        fixedCost(3_000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getLockingCap,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_ACTIVE_POWPEG_REDEEM_SCRIPT(
        AbiFunction.fromSignature(
            "getActivePowpegRedeemScript",
            new String[]{},
            new String[]{"bytes"}
        ),
        fixedCost(30_000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getActivePowpegRedeemScript,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT(
        AbiFunction.fromSignature(
            "getActiveFederationCreationBlockHeight",
            new String[]{},
            new String[]{"uint256"}
        ),
        fixedCost(3_000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getActiveFederationCreationBlockHeight,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    INCREASE_LOCKING_CAP(
        AbiFunction.fromSignature(
            "increaseLockingCap",
            new String[]{"int256"},
            new String[]{"bool"}
        ),
        fixedCost(8_000L),
        (BridgeMethodExecutorTyped<Boolean>) Bridge::increaseLockingCap,
        fixedPermission(false)
    ),
    IS_BTC_TX_HASH_ALREADY_PROCESSED(
        AbiFunction.fromSignature(
            "isBtcTxHashAlreadyProcessed",
            new String[]{"string"},
            new String[]{"bool"}
        ),
        fixedCost(23000L),
        (BridgeMethodExecutorTyped<Boolean>) Bridge::isBtcTxHashAlreadyProcessed,
        fixedPermission(true),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    RECEIVE_HEADERS(
        AbiFunction.fromSignature(
            "receiveHeaders",
            new String[]{"bytes[]"},
            new String[]{}
        ),
        fromMethod(Bridge::receiveHeadersGetCost),
        Bridge.executeIfElse(
                Bridge::receiveHeadersIsPublic,
                (BridgeMethodExecutorVoid) Bridge::receiveHeaders,
                Bridge.activeAndRetiringFederationOnly((BridgeMethodExecutorVoid) Bridge::receiveHeaders, "receiveHeaders")
        ),
        fixedPermission(false)
    ),
    RECEIVE_HEADER(
        AbiFunction.fromSignature(
                "receiveHeader",
                new String[]{"bytes"},
                new String[]{"int256"}
        ),
        fixedCost(10_600L),
        Bridge.executeIfElse(
                Bridge::receiveHeaderIsPublic,
                (BridgeMethodExecutorTyped<Integer>) Bridge::receiveHeader,
                Bridge.activeAndRetiringFederationOnly((BridgeMethodExecutorTyped<Integer>) Bridge::receiveHeader, "receiveHeader")
        ),
        fixedPermission(false)
    ),
    REGISTER_BTC_TRANSACTION(
        AbiFunction.fromSignature(
                "registerBtcTransaction",
                new String[]{"bytes", "int", "bytes"},
                new String[]{}
        ),
        fixedCost(22000L),
        Bridge.executeIfElse(
            Bridge::registerBtcTransactionIsPublic,
            (BridgeMethodExecutorVoid) Bridge::registerBtcTransaction,
            Bridge.activeAndRetiringFederationOnly((BridgeMethodExecutorVoid) Bridge::registerBtcTransaction, "registerBtcTransaction")
        ),
        fixedPermission(false)
    ),
    RELEASE_BTC(
        AbiFunction.fromSignature(
                "releaseBtc",
                new String[]{},
                new String[]{}
        ),
        fixedCost(23000L),
        (BridgeMethodExecutorVoid) Bridge::releaseBtc,
        fixedPermission(false)
    ),
    ROLLBACK_FEDERATION(
        AbiFunction.fromSignature(
                "rollbackFederation",
                new String[]{},
                new String[]{"int256"}
        ),
        fixedCost(12000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::rollbackFederation,
        fixedPermission(false)
    ),
    UPDATE_COLLECTIONS(
        AbiFunction.fromSignature(
                "updateCollections",
                new String[]{},
                new String[]{}
        ),
        fixedCost(48000L),
        Bridge.activeAndRetiringFederationOnly((BridgeMethodExecutorVoid) Bridge::updateCollections, "updateCollections"),
        fixedPermission(false)
    ),
    VOTE_FEE_PER_KB(
        AbiFunction.fromSignature(
                "voteFeePerKbChange",
                new String[]{"int256"},
                new String[]{"int256"}
        ),
        fixedCost(10000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::voteFeePerKbChange,
        fixedPermission(false)
    ),
    REGISTER_BTC_COINBASE_TRANSACTION(
        AbiFunction.fromSignature(
        "registerBtcCoinbaseTransaction",
                new String[]{"bytes", "bytes32", "bytes", "bytes32", "bytes32"},
                new String[]{}
        ),
        fixedCost(10000L),
        (BridgeMethodExecutorVoid) Bridge::registerBtcCoinbaseTransaction,
        fixedPermission(false)
    ),
    HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION(
        AbiFunction.fromSignature(
                "hasBtcBlockCoinbaseTransactionInformation",
                new String[]{"bytes32"},
                new String[]{"bool"}
        ),
        fixedCost(5000L),
        (BridgeMethodExecutorTyped<Boolean>) Bridge::hasBtcBlockCoinbaseTransactionInformation,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_BTC_BLOCKCHAIN_BEST_BLOCK_HEADER(
        AbiFunction.fromSignature(
                "getBtcBlockchainBestBlockHeader",
                new String[0],
                new String[]{"bytes"}
        ),
        fixedCost(3_800L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getBtcBlockchainBestBlockHeader,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_BTC_BLOCKCHAIN_BLOCK_HEADER_BY_HASH(
        AbiFunction.fromSignature(
                "getBtcBlockchainBlockHeaderByHash",
                new String[]{"bytes32"},
                new String[]{"bytes"}
        ),
        fixedCost(4_600L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getBtcBlockchainBlockHeaderByHash,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_BTC_BLOCKCHAIN_BLOCK_HEADER_BY_HEIGHT(
        AbiFunction.fromSignature(
            "getBtcBlockchainBlockHeaderByHeight",
            new String[]{"uint256"},
            new String[]{"bytes"}
        ),
        fixedCost(5_000L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getBtcBlockchainBlockHeaderByHeight,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_BTC_BLOCKCHAIN_PARENT_BLOCK_HEADER_BY_HASH(
        AbiFunction.fromSignature(
                "getBtcBlockchainParentBlockHeaderByHash",
                new String[]{"bytes32"},
                new String[]{"bytes"}
        ),
        fixedCost(4_900L),
        (BridgeMethodExecutorTyped<byte[]>) Bridge::getBtcBlockchainParentBlockHeaderByHash,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_NEXT_PEGOUT_CREATION_BLOCK_NUMBER(
        AbiFunction.fromSignature(
                "getNextPegoutCreationBlockNumber",
                new String[]{},
                new String[]{"uint256"}
        ),
        fixedCost(3_000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getNextPegoutCreationBlockNumber,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_QUEUED_PEGOUTS_COUNT(
        AbiFunction.fromSignature(
                "getQueuedPegoutsCount",
                new String[]{},
                new String[]{"uint256"}
        ),
        fixedCost(3_000L),
        (BridgeMethodExecutorTyped<Integer>) Bridge::getQueuedPegoutsCount,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    ),
    GET_ESTIMATED_FEES_FOR_NEXT_PEGOUT_EVENT(
        AbiFunction.fromSignature(
                "getEstimatedFeesForNextPegOutEvent",
                new String[]{},
                new String[]{"uint256"}
        ),
        fixedCost(10_000L),
        (BridgeMethodExecutorTyped<Long>) Bridge::getEstimatedFeesForNextPegOutEvent,
        fixedPermission(false),
        CallTypeHelper.ALLOW_STATIC_CALL
    );

    private static class CallTypeHelper {
        private static final Predicate<CallKind> ALLOW_STATIC_CALL = callType ->
            callType == CallKind.CALL || callType == CallKind.STATICCALL;
        private static final Predicate<CallKind> RESTRICTED_TO_CALL =  callType -> callType == CallKind.CALL;
    }

    private final AbiFunction function;
    private final CostProvider costProvider;
    private final BridgeMethodExecutor executor;
    private final BridgeCallPermissionProvider callPermissionProvider;
    private final Predicate<CallKind> callTypeVerifier;

    BridgeMethods(
        AbiFunction function,
        CostProvider costProvider,
        BridgeMethodExecutor executor,
        BridgeCallPermissionProvider callPermissionProvider) {

        this(
            function,
            costProvider,
            executor,
            callPermissionProvider,
            CallTypeHelper.RESTRICTED_TO_CALL
        );
    }

    BridgeMethods(
        AbiFunction function,
        CostProvider costProvider,
        BridgeMethodExecutor executor,
        BridgeCallPermissionProvider callPermissionProvider,
        Predicate<CallKind> callTypeVerifier) {

        this.function = function;
        this.costProvider = costProvider;
        this.executor = executor;
        this.callPermissionProvider = callPermissionProvider;
        this.callTypeVerifier = callTypeVerifier;
    }

    public static Optional<BridgeMethods> findBySignature(Bytes encoding) {
        return Optional.ofNullable(SIGNATURES.get(encoding));
    }

    public AbiFunction getFunction() {
        return function;
    }

    public long getCost(Bridge bridge, Object[] args) {
        return costProvider.getCost(bridge, args);
    }

    public BridgeMethodExecutor getExecutor() {
        return executor;
    }

    public boolean onlyAllowsLocalCalls(Bridge bridge, Object[] args) {
        return callPermissionProvider.getOnlyAllowLocalCallsPermission(bridge, args);
    }

    public boolean acceptsThisTypeOfCall(CallKind callType) {
        return callTypeVerifier.test(callType);
    }

    public interface BridgeCondition {
        boolean isTrue(Bridge bridge);
    }

    /**
     * Interface for executing methods in the Bridge context.
     * 
     * <p>
     * This interface defines a single method, {@code execute}, which takes a
     * {@link Bridge} instance and an array of arguments, returning an
     * {@code Optional} result. Implementations of this interface should handle
     * the execution logic and manage potential exceptions.
     * </p>
     */
    public interface BridgeMethodExecutor {
        Optional<?> execute(Bridge self, Object[] args) throws Exception;
    }

    /**
     * A typed variant of {@link BridgeMethodExecutor} that allows for specific
     * return types.
     * 
     * <p>
     * This interface extends {@code BridgeMethodExecutor} and provides a default
     * implementation of the {@code execute} method, delegating the call to a typed
     * execution method {@code executeTyped}. Implementations must define this
     * method to specify the expected return type.
     * </p>
     *
     * @param <T> the return type of the executed method
     */
    private interface BridgeMethodExecutorTyped<T> extends BridgeMethodExecutor {
        @Override
        default Optional<T> execute(Bridge self, Object[] args) throws Exception {
            return Optional.ofNullable(executeTyped(self, args));
        }

        T executeTyped(Bridge self, Object[] args) throws Exception;
    }

    /**
     * A variant of {@link BridgeMethodExecutor} for void methods.
     * 
     * <p>
     * This interface overrides the {@code execute} method to perform an action
     * without returning a result. Implementations should define the
     * {@code executeVoid} method, which executes the intended action using the
     * provided {@link Bridge} instance and arguments.
     * </p>
     */
    private interface BridgeMethodExecutorVoid extends BridgeMethodExecutor {
        @Override
        default Optional<?> execute(Bridge self, Object[] args) throws Exception {
            executeVoid(self, args);
            return Optional.empty();
        }

        void executeVoid(Bridge self, Object[] args) throws Exception;
    }

    private interface CostProvider {
        long getCost(Bridge bridge, Object[] args);
    }

    private interface BridgeCostProvider {
        long getCost(Bridge bridge, Object[] args);
    }

    private static CostProvider fixedCost(long cost) {
        return (Bridge bridge, Object[] args) -> cost;
    }

    private static CostProvider fromMethod(BridgeCostProvider bridgeCostProvider) {
        return (Bridge bridge, Object[] args) -> bridgeCostProvider.getCost(bridge, args);
    }

    private interface BridgeCallPermissionProvider {
        boolean getOnlyAllowLocalCallsPermission(Bridge bridge, Object[] args);
    }

    private static BridgeCallPermissionProvider fixedPermission(boolean onlyAllowsLocalCalls) {
        return (Bridge bridge, Object[] args) -> onlyAllowsLocalCalls;
    }

    private static BridgeCallPermissionProvider fromMethod(BridgeCallPermissionProvider bridgeCallPermissionProvider) {
        return (Bridge bridge, Object[] args) -> bridgeCallPermissionProvider.getOnlyAllowLocalCallsPermission(bridge, args);
    }

    private static final Map<Bytes, BridgeMethods> SIGNATURES = Stream.of(BridgeMethods.values())
        .collect(Collectors.toMap(
            m -> m.getFunction().encodeSignature(),
            Function.identity()
        ));
}
