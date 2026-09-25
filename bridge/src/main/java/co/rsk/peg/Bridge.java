/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
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

import co.rsk.peg.abi.AbiFunction;
import org.apache.tuweni.bytes.Bytes;

import co.rsk.peg.host.BridgeHost;

import co.rsk.peg.host.CallContext;


import static co.rsk.peg.BridgeSerializationUtils.deserializeRskTxHash;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.peg.constants.BridgeConstants;
import org.hyperledger.besu.datatypes.Hash;
import co.rsk.peg.BridgeMethods.BridgeMethodExecutor;
import co.rsk.peg.feeperkb.FeePerKbResponseCode;
import co.rsk.peg.lockingcap.LockingCapIllegalArgumentException;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.peg.bitcoin.MerkleBranch;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationChangeResponseCode;
import co.rsk.peg.federation.FederationMember;
import co.rsk.peg.utils.BtcTransactionFormatUtils;
import com.google.common.annotations.VisibleForTesting;
import co.rsk.peg.utils.Printable;
import co.rsk.peg.host.CallKind;
import co.rsk.peg.exception.VMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Precompiled contract that manages the 2 way peg between bitcoin and RSK.
 * This class is just a wrapper, actual functionality is found in BridgeSupport.
 * @author Oscar Guindzberg
 */
public class Bridge {

    private static final Logger logger = LoggerFactory.getLogger(Bridge.class);

    private static final Integer RECEIVE_HEADER_ERROR_SIZE_MISTMATCH = -20;

    private final BridgeConstants bridgeConstants;

    private CallContext rskTx;

    private BridgeSupport bridgeSupport;
    private final BridgeSupportFactory bridgeSupportFactory;

    private final BiFunction<List<Sha256Hash>, Integer, MerkleBranch> merkleBranchFactory;

    private final BridgeHost host;
    private final boolean localCall;
    private CallKind msgType;

    public Bridge(
        BridgeConstants bridgeConstants,
        BridgeSupportFactory bridgeSupportFactory,
        BridgeHost host) {

        this(
            bridgeConstants,
            bridgeSupportFactory,
            MerkleBranch::new,
            host
        );
    }

    /**
     * A Bridge instance serves exactly one call: it is built on the host of that call and holds the
     * per-call state RSKj used to set through its init method before each execution.
     */
    @VisibleForTesting
    Bridge(
        BridgeConstants bridgeConstants,
        BridgeSupportFactory bridgeSupportFactory,
        BiFunction<List<Sha256Hash>, Integer, MerkleBranch> merkleBranchFactory,
        BridgeHost host) {

        this.bridgeSupportFactory = bridgeSupportFactory;
        this.bridgeConstants = bridgeConstants;
        this.merkleBranchFactory = merkleBranchFactory;
        this.host = host;

        this.rskTx = CallContext.of(host);
        this.localCall = host.isLocalCall();
        this.msgType = host.callKind();
        this.bridgeSupport = bridgeSupportFactory.newInstance(host);
    }

    public long getGasForData(Bytes data) {
        BridgeParsedData bridgeParsedData = parseData(data);

        long functionCost;
        long totalCost;
        if (bridgeParsedData == null) {
            functionCost = BridgeMethods.RELEASE_BTC.getCost(this, new Object[0]);
            totalCost = functionCost;
        } else {
            functionCost = bridgeParsedData.bridgeMethod.getCost(this, bridgeParsedData.args);
            int dataCost = data.size() * 2;

            totalCost = functionCost + dataCost;
        }

        return totalCost;
    }

    @VisibleForTesting
    BridgeParsedData parseData(Bytes data) {
        BridgeParsedData bridgeParsedData = new BridgeParsedData();

        if (data.size() >= 1 && data.size() <= 3) {
            logger.warn("Invalid function signature {}.", Printable.hex(data.toArrayUnsafe()));
            return null;
        }

        if (data.isEmpty()) {
            bridgeParsedData.bridgeMethod = BridgeMethods.RELEASE_BTC;
            bridgeParsedData.args = new Object[]{};
        } else {
            Bytes functionSignature = data.slice(0, 4);
            Optional<BridgeMethods> invokedMethod = BridgeMethods.findBySignature(functionSignature);
            if (!invokedMethod.isPresent()) {
                logger.warn("Invalid function signature {}.", Printable.hex(functionSignature.toArrayUnsafe()));
                return null;
            }
            bridgeParsedData.bridgeMethod = invokedMethod.get();
            try {
                bridgeParsedData.args = bridgeParsedData.bridgeMethod.getFunction().decode(data);
            } catch (Exception e) {
                logger.warn("Invalid function arguments {} for function {}.", Printable.hex(data.toArrayUnsafe()), Printable.hex(functionSignature.toArrayUnsafe()));
                return null;
            }
        }

        return bridgeParsedData;
    }

    // Parsed rsk transaction data field
    private static class BridgeParsedData {
        public BridgeMethods bridgeMethod;
        public Object[] args;
    }

    public Bytes execute(Bytes data) throws VMException {
        try {
            BridgeParsedData bridgeParsedData = parseData(data);

            // Function parsing from data returned null => invalid function selected, halt!
            if (bridgeParsedData == null) {
                String errorMessage = String.format("Invalid data given: %s.", Printable.hex(data.toArrayUnsafe()));
                logger.info("[execute] {}", errorMessage);
                throw new BridgeIllegalArgumentException(errorMessage);
            }

            validateCall(bridgeParsedData);
            Optional<?> result = executeBridgeMethod(bridgeParsedData);
            teardown();

            return result.map(value -> bridgeParsedData.bridgeMethod.getFunction().encodeOutputs(value)).orElse(Bytes.EMPTY);
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            throw new VMException(String.format("Exception executing bridge: %s", ex.getMessage()), ex);
        }
    }

    private void validateCall(BridgeParsedData bridgeParsedData) throws BridgeIllegalArgumentException {
        validateLocalCall(bridgeParsedData);
        validateCallMessageType(bridgeParsedData);
    }

    private void validateLocalCall(BridgeParsedData bridgeParsedData) throws BridgeIllegalArgumentException {
        // If this is not a local call, then check whether the function allows for non-local calls
        if (!isLocalCall() &&
            bridgeParsedData.bridgeMethod.onlyAllowsLocalCalls(this, bridgeParsedData.args)) {

            String errorMessage = String.format(
                "Non-local-call to %s. Returning without execution.",
                bridgeParsedData.bridgeMethod.getFunction().getName()
            );
            logger.info("[validateLocalCall] {}", errorMessage);
            throw new BridgeIllegalArgumentException(errorMessage);
        }
    }

    private void validateCallMessageType(BridgeParsedData bridgeParsedData) throws BridgeIllegalArgumentException {
        if (!bridgeParsedData.bridgeMethod.acceptsThisTypeOfCall(this.msgType)) {
            String errorMessage = String.format(
                "Call type (%s) not accepted by %s. Returning without execution.",
                this.msgType.name(),
                bridgeParsedData.bridgeMethod.getFunction().getName()
            );
            logger.info("[validateCallMessageType] {}", errorMessage);

            throw new BridgeIllegalArgumentException(errorMessage);
        }
    }

    private Optional<?> executeBridgeMethod(BridgeParsedData bridgeParsedData) throws Exception {
        try {
            // bridgeParsedData.function should be one of the AbiFunction declared above.
            // If the user tries to call an non-existent function, parseData() will return null.
            BridgeMethodExecutor executor = bridgeParsedData.bridgeMethod.getExecutor();
            return executor.execute(this, bridgeParsedData.args);
        } catch (BridgeIllegalArgumentException ex) {
            String errorMessage = String.format("Error executing: %s", bridgeParsedData.bridgeMethod);
            logger.warn(errorMessage, ex);
            
            throw new BridgeIllegalArgumentException(errorMessage);
        }
    }

    private void teardown() {
        bridgeSupport.save();
    }

    public void updateCollections(Object[] args) throws VMException {
        logger.trace("updateCollections");

        try {
            bridgeSupport.updateCollections(rskTx);
        } catch (Exception e) {
            logger.warn("Exception onBlock", e);
            throw new VMException("Exception onBlock", e);
        }
    }

    public boolean receiveHeadersIsPublic() {
        return false;
    }

    /*
     * We do not open receiveHeader bridge operation due to security reasons
     */
    public boolean receiveHeaderIsPublic() {
        return false; 
    }

    public long receiveHeadersGetCost(Object[] args) {
        final long BASE_COST = 25_000L;
        if (args == null) {
            return BASE_COST;
        }

        final int numberOfHeaders = ((Object[]) args[0]).length;

        if (numberOfHeaders == 0) {
            return BASE_COST;
        }
        // Dynamic cost based on the number of headers
        // We add each additional header times 3500 to the base cost
        final long COST_PER_ADDITIONAL_HEADER = 3_500;
        return BASE_COST + (numberOfHeaders - 1) * COST_PER_ADDITIONAL_HEADER;
    }

    public void receiveHeaders(Object[] args) throws VMException {
        logger.trace("receiveHeaders");

        Object[] btcBlockSerializedArray = (Object[]) args[0];

        // Before going and actually deserializing and calling the underlying function,
        // check that all block headers passed in are actually block headers doing
        // a simple size check. If this check fails, just fail.
        if (Arrays.stream(btcBlockSerializedArray).anyMatch(bytes -> !BtcTransactionFormatUtils.isBlockHeaderSize(((byte[]) bytes).length))) {
            // This exception type bypasses bridge teardown, signalling no work done
            // and preventing the overhead of saving bridge storage
            logger.warn("Unexpected BTC header(s) received (size mismatch). Aborting processing.");
            throw new BridgeIllegalArgumentException("Unexpected BTC header(s) received (size mismatch). Aborting processing.");
        }

        BtcBlock[] btcBlockArray = new BtcBlock[btcBlockSerializedArray.length];
        for (int i = 0; i < btcBlockSerializedArray.length; i++) {
            byte[] btcBlockSerialized = (byte[]) btcBlockSerializedArray[i];
            try {
                BtcBlock header = bridgeConstants.getBtcParams().getDefaultSerializer().makeBlock(btcBlockSerialized);
                btcBlockArray[i] = header;
            } catch (ProtocolException e) {
                throw new BridgeIllegalArgumentException("Block " + i + " could not be parsed " + Printable.hex(btcBlockSerialized), e);
            }
        }
        try {
            bridgeSupport.receiveHeaders(btcBlockArray);
        } catch (Exception e) {
            logger.warn("Exception adding header", e);
            throw new VMException("Exception adding header", e);
        }
    }

    public boolean registerBtcTransactionIsPublic() {
        return true;
    }

    public int receiveHeader(Object[] args) throws VMException {
        logger.trace("receiveHeader");

        byte[] headerArg = (byte[]) args[0];

        if (!BtcTransactionFormatUtils.isBlockHeaderSize(headerArg.length)) {
            logger.warn("Unexpected BTC header received (size mismatch). Aborting processing.");
            return RECEIVE_HEADER_ERROR_SIZE_MISTMATCH;
        }

        BtcBlock header = bridgeConstants.getBtcParams().getDefaultSerializer().makeBlock(headerArg);

        try {
            return bridgeSupport.receiveHeader(header);
        } catch (Exception e) {
            String errorMessage = "Exception adding header in receiveHeader";
            logger.warn(errorMessage, e);
            throw new VMException(errorMessage, e);
        }
    }

    public void registerBtcTransaction(Object[] args) throws VMException {
        logger.trace("registerBtcTransaction");

        byte[] btcTxSerialized = (byte[]) args[0];
        int height = ((BigInteger)args[1]).intValue();

        byte[] pmtSerialized = (byte[]) args[2];
        try {
            bridgeSupport.registerBtcTransaction(rskTx, btcTxSerialized, height, pmtSerialized);
        } catch (IOException | BlockStoreException e) {
            logger.warn("Exception in registerBtcTransaction", e);
            throw new VMException("Exception in registerBtcTransaction", e);
        }
    }

    public void releaseBtc(Object[] args) throws VMException {
        logger.trace("releaseBtc");

        try {
            bridgeSupport.releaseBtc(rskTx);
        } catch (Exception e) {
            logger.warn("Exception in releaseBtc", e);
            throw new VMException("Exception in releaseBtc", e);
        }
    }

    public void addSignature(Object[] args) throws VMException {
        logger.trace("addSignature");

        byte[] federatorPublicKeySerialized = (byte[]) args[0];
        BtcECKey federatorPublicKey;
        try {
            federatorPublicKey = BtcECKey.fromPublicOnly(federatorPublicKeySerialized);
        } catch (Exception e) {
            throw new BridgeIllegalArgumentException("Public key could not be parsed " + Printable.hex(federatorPublicKeySerialized), e);
        }
        Object[] signaturesObjectArray = (Object[]) args[1];
        if (signaturesObjectArray.length == 0) {
            throw new BridgeIllegalArgumentException("Signatures array is empty");
        }
        List<byte[]> signatures = new ArrayList<>();
        for (Object signatureObject : signaturesObjectArray) {
            byte[] signatureByteArray = (byte[])signatureObject;
            try {
                BtcECKey.ECDSASignature.decodeFromDER((byte[])signatureObject);
            } catch (Exception e) {
                throw new BridgeIllegalArgumentException("Signature could not be parsed " + Printable.hex(signatureByteArray), e);
            }
            signatures.add(signatureByteArray);
        }
        byte[] rskTxHashSerialized = (byte[]) args[2];
        Hash rskTxHash;
        try {
            rskTxHash = deserializeRskTxHash(rskTxHashSerialized);
        } catch (IllegalArgumentException e) {
            throw new BridgeIllegalArgumentException("Invalid rsk tx hash " + Printable.hex(rskTxHashSerialized));
        }
        try {
            bridgeSupport.addSignature(federatorPublicKey, signatures, rskTxHash);
        } catch (Exception e) {
            logger.warn("Exception in addSignature", e);
            throw new VMException("Exception in addSignature", e);
        }
    }

    public byte[] getStateForBtcReleaseClient(Object[] args) throws VMException {
        logger.trace("getStateForBtcReleaseClient");

        try {
            return bridgeSupport.getStateForBtcReleaseClient();
        } catch (Exception e) {
            logger.warn("Exception in getStateForBtcReleaseClient", e);
            throw new VMException("Exception in getStateForBtcReleaseClient", e);
        }
    }

    public byte[] getStateForSvpClient(Object[] args) throws VMException {
        logger.trace("getStateForSvpClient");

        try {
            return bridgeSupport.getStateForSvpClient();
        } catch (Exception e) {
            logger.warn("Exception in getStateForSvpClient", e);
            throw new VMException("Exception in getStateForSvpClient", e);
        }
    }

    public byte[] getStateForDebugging(Object[] args) throws VMException {
        logger.trace("getStateForDebugging");

        try {
            return bridgeSupport.getStateForDebugging();
        } catch (Exception e) {
            logger.warn("Exception in getStateForDebugging", e);
            throw new VMException("Exception in getStateForDebugging", e);
        }
    }

    public Integer getBtcBlockchainBestChainHeight(Object[] args) throws VMException {
        logger.trace("getBtcBlockchainBestChainHeight");

        try {
            return bridgeSupport.getBtcBlockchainBestChainHeight();
        } catch (Exception e) {
            logger.warn("Exception in getBtcBlockchainBestChainHeight", e);
            throw new VMException("Exception in getBtcBlockchainBestChainHeight", e);
        }
    }

    public boolean getBtcBlockchainBestChainHeightOnlyAllowsLocalCalls(Object[] args) {
        return false;
    }

    public Integer getBtcBlockchainInitialBlockHeight(Object[] args) throws VMException {
        logger.trace("getBtcBlockchainInitialBlockHeight");

        try {
            return bridgeSupport.getBtcBlockchainInitialBlockHeight();
        } catch (Exception e) {
            logger.warn("Exception in getBtcBlockchainInitialBlockHeight", e);
            throw new VMException("Exception in getBtcBlockchainInitialBlockHeight", e);
        }
    }

    public byte[] getBtcBlockchainBlockHashAtDepth(Object[] args) throws VMException {
        logger.trace("getBtcBlockchainBlockHashAtDepth");

        int depth = ((BigInteger) args[0]).intValue();
        Sha256Hash blockHash;
        try {
            blockHash = bridgeSupport.getBtcBlockchainBlockHashAtDepth(depth);
        } catch (Exception e) {
            logger.warn("Exception in getBtcBlockchainBlockHashAtDepth", e);
            throw new VMException("Exception in getBtcBlockchainBlockHashAtDepth", e);
        }

        return blockHash.getBytes();
    }

    public long getBtcTransactionConfirmationsGetCost(Object[] args) {
        return bridgeSupport.getBtcTransactionConfirmationsGetCost(args);
    }

    public int getBtcTransactionConfirmations(Object[] args) throws VMException {
        logger.trace("getBtcTransactionConfirmations");
        try {
            Sha256Hash btcTxHash = Sha256Hash.wrap((byte[]) args[0]);
            Sha256Hash btcBlockHash = Sha256Hash.wrap((byte[]) args[1]);

            int merkleBranchPath = ((BigInteger) args[2]).intValue();

            Object[] merkleBranchHashesArray = (Object[]) args[3];
            List<Sha256Hash> merkleBranchHashes = Arrays.stream(merkleBranchHashesArray)
                    .map(hash -> Sha256Hash.wrap((byte[]) hash)).collect(Collectors.toList());

            MerkleBranch merkleBranch = merkleBranchFactory.apply(merkleBranchHashes, merkleBranchPath);

            return bridgeSupport.getBtcTransactionConfirmations(btcTxHash, btcBlockHash, merkleBranch);
        } catch (Exception e) {
            logger.warn("Exception in getBtcTransactionConfirmations", e);
            throw new VMException("Exception in getBtcTransactionConfirmations", e);
        }
    }

    public Long getMinimumLockTxValue(Object[] args) {
        logger.trace("getMinimumLockTxValue");
        return bridgeConstants.getMinimumPeginTxValue().getValue();
    }

    public Boolean isBtcTxHashAlreadyProcessed(Object[] args) throws VMException {
        logger.trace("isBtcTxHashAlreadyProcessed");

        try {
            Sha256Hash btcTxHash = Sha256Hash.wrap((String) args[0]);
            return bridgeSupport.isBtcTxHashAlreadyProcessed(btcTxHash);
        } catch (Exception e) {
            logger.warn("Exception in isBtcTxHashAlreadyProcessed", e);
            throw new VMException("Exception in isBtcTxHashAlreadyProcessed", e);
        }
    }

    public Long getBtcTxHashProcessedHeight(Object[] args) throws VMException {
        logger.trace("getBtcTxHashProcessedHeight");

        try {
            Sha256Hash btcTxHash = Sha256Hash.wrap((String) args[0]);
            return bridgeSupport.getBtcTxHashProcessedHeight(btcTxHash);
        } catch (Exception e) {
            logger.warn("Exception in getBtcTxHashProcessedHeight", e);
            throw new VMException("Exception in getBtcTxHashProcessedHeight", e);
        }
    }

    public String getFederationAddress(Object[] args) {
        logger.trace("getFederationAddress");

        return bridgeSupport.getActiveFederationAddress().toBase58();
    }

    public Integer getFederationSize(Object[] args) {
        logger.trace("getFederationSize");

        return bridgeSupport.getActiveFederationSize();
    }

    public Integer getFederationThreshold(Object[] args) {
        logger.trace("getFederationThreshold");

        return bridgeSupport.getActiveFederationThreshold();
    }

    public byte[] getFederatorPublicKeyOfType(Object[] args) throws VMException {
        logger.trace("getFederatorPublicKeyOfType");

        int index = ((BigInteger) args[0]).intValue();

        FederationMember.KeyType keyType;
        try {
            keyType = FederationMember.KeyType.byValue((String) args[1]);
        } catch (Exception e) {
            logger.warn("Exception in getFederatorPublicKeyOfType", e);
            throw new VMException("Exception in getFederatorPublicKeyOfType", e);
        }

        return bridgeSupport.getActiveFederatorPublicKeyOfType(index, keyType);
    }

    public Long getFederationCreationTime(Object[] args) {
        logger.trace("getFederationCreationTime");
        Instant activeFederationCreationTime = bridgeSupport.getActiveFederationCreationTime();

        // Return the creation time in seconds from the epoch
        return activeFederationCreationTime.getEpochSecond();
    }

    public long getFederationCreationBlockNumber(Object[] args) {
        logger.trace("getFederationCreationBlockNumber");
        return bridgeSupport.getActiveFederationCreationBlockNumber();
    }

    public String getRetiringFederationAddress(Object[] args) {
        logger.trace("getRetiringFederationAddress");

        Address address = bridgeSupport.getRetiringFederationAddress();

        if (address == null) {
            // When there's no address, empty string is returned
            return "";
        }

        return address.toBase58();
    }

    public Integer getRetiringFederationSize(Object[] args) {
        logger.trace("getRetiringFederationSize");

        return bridgeSupport.getRetiringFederationSize();
    }

    public Integer getRetiringFederationThreshold(Object[] args) {
        logger.trace("getRetiringFederationThreshold");

        return bridgeSupport.getRetiringFederationThreshold();
    }

    public byte[] getRetiringFederatorPublicKeyOfType(Object[] args) throws VMException {
        logger.trace("getRetiringFederatorPublicKeyOfType");

        int index = ((BigInteger) args[0]).intValue();

        FederationMember.KeyType keyType;
        try {
            keyType = FederationMember.KeyType.byValue((String) args[1]);
        } catch (Exception e) {
            logger.warn("Exception in getRetiringFederatorPublicKeyOfType", e);
            throw new VMException("Exception in getRetiringFederatorPublicKeyOfType", e);
        }

        byte[] publicKey = bridgeSupport.getRetiringFederatorPublicKeyOfType(index, keyType);

        if (publicKey == null) {
            // Empty array is returned when public key is not found or there's no retiring federation
            return new byte[]{};
        }

        return publicKey;
    }

    public Long getRetiringFederationCreationTime(Object[] args) {
        logger.trace("getRetiringFederationCreationTime");

        Instant retiringFederationCreationTime = bridgeSupport.getRetiringFederationCreationTime();

        if (retiringFederationCreationTime == null) {
            // -1 is returned when no retiring federation
            return -1L;
        }

        // Return the creation time in seconds from the epoch
        return retiringFederationCreationTime.getEpochSecond();
    }

    public long getRetiringFederationCreationBlockNumber(Object[] args) {
        logger.trace("getRetiringFederationCreationBlockNumber");
        return bridgeSupport.getRetiringFederationCreationBlockNumber();
    }

    public Integer createFederation(Object[] args) {
        logger.trace("createFederation");

        return bridgeSupport.voteFederationChange(
            rskTx,
            new ABICallSpec("create", new byte[][]{})
        );
    }

    public Integer addFederatorPublicKeyMultikey(Object[] args) {
        logger.trace("addFederatorPublicKeyMultikey");

        byte[] btcPublicKeyBytes = (byte[]) args[0];
        byte[] rskPublicKeyBytes = (byte[]) args[1];
        byte[] mstPublicKeyBytes = (byte[]) args[2];

        return bridgeSupport.voteFederationChange(
            rskTx,
            new ABICallSpec(
                "add-multi",
                new byte[][]{ btcPublicKeyBytes, rskPublicKeyBytes, mstPublicKeyBytes }
            )
        );
    }

    public Integer commitFederation(Object[] args) {
        logger.trace("commitFederation");

        byte[] hash;
        try {
            hash = (byte[]) args[0];
        } catch (Exception e) {
            logger.warn("Exception in commitFederation", e);
            return -10;
        }

        return bridgeSupport.voteFederationChange(
            rskTx,
            new ABICallSpec("commit", new byte[][]{ hash })
        );
    }

    public Integer rollbackFederation(Object[] args) {
        logger.trace("rollbackFederation");

        return bridgeSupport.voteFederationChange(
                rskTx,
                new ABICallSpec("rollback", new byte[][]{})
        );
    }

    public byte[] getPendingFederationHashSerialized(Object[] args) {
        logger.trace("getPendingFederationHash");

        Hash hash = bridgeSupport.getPendingFederationHash();

        if (hash == null) {
            // Empty array is returned when pending federation is not present
            return new byte[]{};
        }

        return hash.getBytes().toArrayUnsafe();
    }

    public Integer getPendingFederationSize(Object[] args) {
        logger.trace("getPendingFederationSize");

        return bridgeSupport.getPendingFederationSize();
    }

    public byte[] getPendingFederatorPublicKeyOfType(Object[] args) throws VMException {
        logger.trace("getPendingFederatorPublicKeyOfType");

        int index = ((BigInteger) args[0]).intValue();

        FederationMember.KeyType keyType;
        try {
            keyType = FederationMember.KeyType.byValue((String) args[1]);
        } catch (Exception e) {
            logger.warn("Exception in getPendingFederatorPublicKeyOfType", e);
            throw new VMException("Exception in getPendingFederatorPublicKeyOfType", e);
        }

        byte[] publicKey = bridgeSupport.getPendingFederatorPublicKeyOfType(index, keyType);

        if (publicKey == null) {
            // Empty array is returned when public key is not found
            return new byte[]{};
        }

        return publicKey;
    }

    /**
     * Retrieves the proposed federation Bitcoin address as a Base58 string.
     *
     * <p>
     * This method attempts to fetch the address of the proposed federation. If the 
     * proposed federation is present, it converts the address to its Base58 representation.
     * If not, an empty string is returned.
     * <p>
     *
     * @param args Additional arguments (currently unused)
     * @return The Base58 encoded Bitcoin address of the proposed federation, or an empty 
     *         string if no proposed federation is present.
     */
    public String getProposedFederationAddress(Object[] args) {
        logger.trace("getProposedFederationAddress");
        
        return bridgeSupport.getProposedFederationAddress()
            .map(Address::toBase58)
            .orElse("");
    }

    /**
     * Retrieves the size of the proposed federation, if it exists.
     *
     * <p>
     * This method returns the number of members in the proposed federation. If no proposed federation exists,
     * it returns a default response code {@link FederationChangeResponseCode#FEDERATION_NON_EXISTENT} that indicates
     * the federation does not exist.
     * </p>
     *
     * @param args unused arguments for this method (can be null or empty).
     * @return the size of the proposed federation (number of members), or the default code from
     *         {@link FederationChangeResponseCode#FEDERATION_NON_EXISTENT} if no proposed federation is available.
     */
    public int getProposedFederationSize(Object[] args) {
        logger.trace("getProposedFederationSize");

        return bridgeSupport.getProposedFederationSize()
            .orElse(FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode());
    }

    /**
     * Retrieves the creation time of the proposed federation in seconds since the epoch.
     *
     * <p>
     * This method checks if a proposed federation exists and returns its creation time in
     * seconds since the Unix epoch. If no proposed federation exists, it returns -1.
     * </p>
     *
     * @param args unused arguments for this method (can be null or empty).
     * @return the creation time of the proposed federation in seconds since the epoch,
     *         or -1 if no proposed federation exists.
     */
    public Long getProposedFederationCreationTime(Object[] args) {
        logger.trace("getProposedFederationCreationTime");

        return bridgeSupport.getProposedFederationCreationTime()
            .map(Instant::getEpochSecond)
            .orElse(-1L);
    }

    /**
     * Retrieves the block number of the proposed federation's creation.
     *
     * <p>
     * This method checks if a proposed federation exists and returns the block number at which it was created.
     * If no proposed federation exists, it returns the default code defined in
     * {@link FederationChangeResponseCode#FEDERATION_NON_EXISTENT}.
     * </p>
     *
     * @param args unused arguments for this method (can be null or empty).
     * @return the block number of the proposed federation's creation, or
     *         the code from {@link FederationChangeResponseCode#FEDERATION_NON_EXISTENT}
     *         if no proposed federation exists.
     */
    public long getProposedFederationCreationBlockNumber(Object[] args) {
        logger.trace("getProposedFederationCreationBlockNumber");

        return bridgeSupport.getProposedFederationCreationBlockNumber()
            .orElse((long) FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode());
    }

    /**
     * Retrieves the public key of the proposed federator at the specified index and key type.
     *
     * <p>
     * This method extracts the index and key type from the provided arguments, retrieves the
     * public key of the proposed federator, and returns it. If no public key is found, an empty byte
     * array is returned.
     * </p>
     *
     * <p>
     * The first argument in the {@code args} array is expected to be a {@link BigInteger} representing
     * the federator's index. The second argument is expected to be a {@link String} representing
     * the key type, which is converted into a {@link FederationMember.KeyType}.
     * </p>
     *
     * @param args an array of arguments, where {@code args[0]} is a {@link BigInteger} for the federator's index,
     *             and {@code args[1]} is a {@link String} for the key type.
     * @return a byte array containing the federator's public key, or an empty byte array if not found.
     * @throws VMException if an error occurs while processing the key type or if getting an index out of bound exception from method call.
     */
    public byte[] getProposedFederatorPublicKeyOfType(Object[] args) throws VMException {
        logger.trace("getProposedFederatorPublicKeyOfType");

        int index = ((BigInteger) args[0]).intValue();

        FederationMember.KeyType keyType;
        try {
            keyType = FederationMember.KeyType.byValue((String) args[1]);
        } catch (Exception e) {
            String errorMessage = "[getProposedFederatorPublicKeyOfType] Exception processing public key type";
            throw new VMException(errorMessage, e);
        }

        Optional<byte[]> publicKey;
        try {
            publicKey = bridgeSupport.getProposedFederatorPublicKeyOfType(index, keyType);
        } catch (IndexOutOfBoundsException e) {
            String errorMessage = String.format(
                "[getProposedFederatorPublicKeyOfType] Exception getting the %s key of member %d", keyType, index
            );
            throw new VMException(errorMessage, e);
        }

        return publicKey
            .orElse(new byte[]{});
    }

    public Integer voteFeePerKbChange(Object[] args) {
        logger.trace("voteFeePerKbChange");

        Coin feePerKb;
        try {
            feePerKb = Coin.valueOf(((BigInteger) args[0]).longValueExact());
        } catch (Exception e) {
            logger.warn("Exception in voteFeePerKbChange", e);
            return FeePerKbResponseCode.GENERIC_ERROR.getCode();
        }

        return bridgeSupport.voteFeePerKbChange(rskTx, feePerKb);
    }

    public long getFeePerKb(Object[] args) {
        logger.trace("getFeePerKb");

        return bridgeSupport.getFeePerKb().getValue();
    }

    public long getLockingCap(Object[] args) {
        logger.trace("getLockingCap");

        Coin lockingCap = bridgeSupport.getLockingCap();

        return lockingCap.getValue();
    }

    public byte[] getActivePowpegRedeemScript(Object[] args) {
        logger.debug("[getActivePowpegRedeemScript] started");
        try {
            Optional<Script> redeemScript = bridgeSupport.getActiveFederationRedeemScript();
            logger.debug("[getActivePowpegRedeemScript] finished");
            return redeemScript.orElse(new Script(new byte[]{})).getProgram();
        } catch (Exception ex) {
            logger.warn("[getActivePowpegRedeemScript] something failed", ex);
            throw ex;
        }
    }

    public boolean increaseLockingCap(Object[] args) throws BridgeIllegalArgumentException {
        logger.trace("increaseLockingCap");
        Coin newLockingCap = BridgeUtils.getCoinFromBigInteger((BigInteger) args[0]);
        try {
            return bridgeSupport.increaseLockingCap(rskTx, newLockingCap);
        } catch (LockingCapIllegalArgumentException e) {
            throw new BridgeIllegalArgumentException(e);
        }
    }

    public void registerBtcCoinbaseTransaction(Object[] args) throws VMException {
        logger.trace("registerBtcCoinbaseTransaction");

        byte[] btcTxSerialized = (byte[]) args[0];
        Sha256Hash blockHash = Sha256Hash.wrap((byte[]) args[1]);
        byte[] pmtSerialized = (byte[]) args[2];
        Sha256Hash witnessMerkleRoot = Sha256Hash.wrap((byte[]) args[3]);
        byte[] witnessReservedValue = (byte[]) args[4];

        bridgeSupport.registerBtcCoinbaseTransaction(
            btcTxSerialized,
            blockHash,
            pmtSerialized,
            witnessMerkleRoot,
            witnessReservedValue
        );
    }

    public boolean hasBtcBlockCoinbaseTransactionInformation(Object[] args) {
        logger.trace("hasBtcBlockCoinbaseTransactionInformation");
        Sha256Hash blockHash = Sha256Hash.wrap((byte[]) args[0]);

        return bridgeSupport.hasBtcBlockCoinbaseTransactionInformation(blockHash);
    }

    public long getActiveFederationCreationBlockHeight(Object[] args) {
        logger.trace("getActiveFederationCreationBlockHeight");

        return bridgeSupport.getActiveFederationCreationBlockHeight();
    }


    public byte[] getBtcBlockchainBestBlockHeader(Object[] args) {
        logger.trace("getBtcBlockchainBestBlockHeader");

        try {
            return this.bridgeSupport.getBtcBlockchainBestBlockHeader();
        } catch (Exception e) {
            logger.warn("Exception in getBtcBlockchainBestBlockHeader", e);
            return new byte[0];
        }
    }

    public byte[] getBtcBlockchainBlockHeaderByHash(Object[] args) {
        logger.trace("getBtcBlockchainBlockHeaderByHash");

        try {
            byte[] hashBytes = (byte[])args[0];
            Sha256Hash hash = Sha256Hash.wrap(hashBytes);

            return this.bridgeSupport.getBtcBlockchainBlockHeaderByHash(hash);
        } catch (Exception e) {
            logger.warn("Exception in getBtcBlockchainBlockHeaderByHash", e);
            return new byte[0];
        }
    }

    public byte[] getBtcBlockchainBlockHeaderByHeight(Object[] args) {
        logger.trace("getBtcBlockchainBlockHeaderByHeight");

        try {
            int height = ((BigInteger) args[0]).intValue();

            return this.bridgeSupport.getBtcBlockchainBlockHeaderByHeight(height);
        } catch (Exception e) {
            logger.warn("Exception in getBtcBlockchainBlockHeaderByHeight", e);
            return new byte[0];
        }
    }

    public byte[] getBtcBlockchainParentBlockHeaderByHash(Object[] args) {
        logger.trace("getBtcBlockchainParentBlockHeaderByHash");

        try {
            byte[] hashBytes = (byte[])args[0];
            Sha256Hash hash = Sha256Hash.wrap(hashBytes);

            return this.bridgeSupport.getBtcBlockchainParentBlockHeaderByHash(hash);
        } catch (Exception e) {
            logger.warn("Exception in getBtcBlockchainParentBlockHeaderByHash", e);
            return new byte[0];
        }
    }

    public long getNextPegoutCreationBlockNumber(Object[] args) {
        logger.trace("getNextPegoutCreationBlockNumber");

        return bridgeSupport.getNextPegoutCreationBlockNumber();
    }

    public int getQueuedPegoutsCount(Object[] args) throws IOException {
        logger.trace("getQueuedPegoutsCount");

        return bridgeSupport.getQueuedPegoutsCount();
    }

    public long getEstimatedFeesForNextPegOutEvent(Object[] args) throws IOException {
        logger.trace("getEstimatedFeesForNextPegOutEvent");

        return bridgeSupport.getEstimatedFeesForNextPegOutEvent().value;
    }

    public static BridgeMethods.BridgeMethodExecutor activeAndRetiringFederationOnly(BridgeMethods.BridgeMethodExecutor decoratee, String funcName) {
        return (self, args) -> {
            boolean isFromActiveFed = BridgeUtils.isFromFederateMember(self.rskTx, self.bridgeSupport.getActiveFederation());

            Federation retiringFederation = self.bridgeSupport.getRetiringFederation();
            boolean isFromRetiringFed = retiringFederation != null && BridgeUtils.isFromFederateMember(self.rskTx, retiringFederation);

            if (!isFromActiveFed && !isFromRetiringFed) {
                String errorMessage = String.format(
                    "The sender is not a member of the active or retiring federations and is therefore not authorized to invoke the function: '%s'",
                    funcName
                );
                logger.warn(errorMessage);
                throw new VMException(errorMessage);
            }

            return decoratee.execute(self, args);
        };
    }

    public static BridgeMethods.BridgeMethodExecutor activeRetiringAndProposedFederationOnly(BridgeMethods.BridgeMethodExecutor decoratee, String funcName) {
        return (self, args) -> {
            boolean isFromActiveFed = BridgeUtils.isFromFederateMember(self.rskTx, self.bridgeSupport.getActiveFederation());

            Federation retiringFederation = self.bridgeSupport.getRetiringFederation();
            boolean isFromRetiringFed = retiringFederation != null && BridgeUtils.isFromFederateMember(self.rskTx, retiringFederation);

            Optional<Federation> proposedFederation = self.bridgeSupport.getProposedFederation();
            boolean isFromProposedFed = proposedFederation.isPresent() && BridgeUtils.isFromFederateMember(self.rskTx, proposedFederation.get());

            if (!isFromActiveFed && !isFromRetiringFed && !isFromProposedFed) {
                String errorMessage = String.format(
                    "The sender is not a member of the active, retiring, or proposed federations and is therefore not authorized to call the function: '%s'",
                    funcName
                );
                logger.warn(errorMessage);
                throw new VMException(errorMessage);
            }

            return decoratee.execute(self, args);
        };
    }

    public static BridgeMethods.BridgeMethodExecutor executeIfElse(
            BridgeMethods.BridgeCondition condition,
            BridgeMethods.BridgeMethodExecutor ifTrue,
            BridgeMethods.BridgeMethodExecutor ifFalse) {

        return (self, args) -> {
            if (condition.isTrue(self)) {
                return ifTrue.execute(self, args);
            } else {
                return ifFalse.execute(self, args);
            }
        };
    }

    private boolean isLocalCall() {
        return localCall;
    }
}
