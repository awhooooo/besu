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


import co.rsk.peg.host.BridgeHost;

import co.rsk.peg.host.CallContext;

import co.rsk.peg.utils.Weis;

import org.hyperledger.besu.datatypes.Wei;

import static co.rsk.peg.BridgeUtils.calculatePegoutTxSize;
import static co.rsk.peg.BridgeUtils.getRegularPegoutTxSize;
import static co.rsk.peg.PegUtils.*;
import static co.rsk.peg.ReleaseTransactionBuilder.BTC_TX_VERSION_2;
import static co.rsk.peg.bitcoin.BitcoinUtils.*;
import static co.rsk.peg.bitcoin.UtxoUtils.extractOutpointValues;
import static java.util.Objects.isNull;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.crypto.TransactionSignature;
import co.rsk.bitcoinj.script.*;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.peg.bitcoin.*;
import co.rsk.peg.constants.BridgeConstants;
import org.hyperledger.besu.datatypes.Hash;
import co.rsk.peg.btcLockSender.BtcLockSender.TxSenderAddressType;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.FeePerKbSupport;
import co.rsk.peg.lockingcap.LockingCapIllegalArgumentException;
import co.rsk.peg.lockingcap.LockingCapSupport;
import co.rsk.peg.pegin.*;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import co.rsk.peg.utils.*;
import co.rsk.peg.vote.*;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.SignatureException;
import java.time.Instant;
import java.util.*;
import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Pair;
import co.rsk.peg.utils.Printable;
import co.rsk.peg.utils.RskRlp;
import org.apache.tuweni.bytes.Bytes;
import co.rsk.peg.exception.VMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to move funds from btc to rsk and rsk to btc
 * @author Oscar Guindzberg
 */
public class BridgeSupport {
    public static final org.hyperledger.besu.datatypes.Address BURN_ADDRESS = org.hyperledger.besu.datatypes.Address.fromHexString("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");


    public static final Integer BTC_TRANSACTION_CONFIRMATION_INEXISTENT_BLOCK_HASH_ERROR_CODE = -1;
    public static final Integer BTC_TRANSACTION_CONFIRMATION_BLOCK_NOT_IN_BEST_CHAIN_ERROR_CODE = -2;
    public static final Integer BTC_TRANSACTION_CONFIRMATION_INCONSISTENT_BLOCK_ERROR_CODE = -3;
    public static final Integer BTC_TRANSACTION_CONFIRMATION_BLOCK_TOO_OLD_ERROR_CODE = -4;
    public static final Integer BTC_TRANSACTION_CONFIRMATION_INVALID_MERKLE_BRANCH_ERROR_CODE = -5;

    public static final Integer RECEIVE_HEADER_CALLED_TOO_SOON = -1;
    public static final Integer RECEIVE_HEADER_BLOCK_TOO_OLD = -2;
    public static final Integer RECEIVE_HEADER_CANT_FOUND_PREVIOUS_BLOCK = -3;
    public static final Integer RECEIVE_HEADER_BLOCK_PREVIOUSLY_SAVED = -4;
    public static final Integer RECEIVE_HEADER_UNEXPECTED_EXCEPTION = -99;

    // Enough depth to be able to search backwards one month worth of blocks
    // (6 blocks/hour, 24 hours/day, 30 days/month)
    public static final Integer BTC_TRANSACTION_CONFIRMATION_MAX_DEPTH = 4320;

    private static final Logger logger = LoggerFactory.getLogger(BridgeSupport.class);

    private final BridgeConstants bridgeConstants;
    private final NetworkParameters networkParameters;
    private final BridgeStorageProvider provider;
    private final BridgeHost host;
    private final BridgeEventLogger eventLogger;
    private final BtcLockSenderProvider btcLockSenderProvider;
    private final PeginInstructionsProvider peginInstructionsProvider;

    private final FeePerKbSupport feePerKbSupport;
    private final FederationSupport federationSupport;
    private final LockingCapSupport lockingCapSupport;

    private final Context btcContext;
    private final BtcBlockStoreWithCache.Factory btcBlockStoreFactory;
    private final BootstrapWindow bootstrapWindow;
    private BtcBlockStoreWithCache btcBlockStore;
    private BtcBlockChain btcBlockChain;


    public BridgeSupport(
        BridgeConstants bridgeConstants,
        BridgeStorageProvider provider,
        BridgeEventLogger eventLogger,
        BtcLockSenderProvider btcLockSenderProvider,
        PeginInstructionsProvider peginInstructionsProvider,
        BridgeHost host,
        Context btcContext,
        FeePerKbSupport feePerKbSupport,
        FederationSupport federationSupport,
        LockingCapSupport lockingCapSupport,
        BtcBlockStoreWithCache.Factory btcBlockStoreFactory) {
        this(bridgeConstants, provider, eventLogger, btcLockSenderProvider, peginInstructionsProvider,
            host, btcContext, feePerKbSupport, federationSupport, lockingCapSupport,
            btcBlockStoreFactory, BootstrapWindow.CLOSED);
    }

    public BridgeSupport(
        BridgeConstants bridgeConstants,
        BridgeStorageProvider provider,
        BridgeEventLogger eventLogger,
        BtcLockSenderProvider btcLockSenderProvider,
        PeginInstructionsProvider peginInstructionsProvider,
        BridgeHost host,
        Context btcContext,
        FeePerKbSupport feePerKbSupport,
        FederationSupport federationSupport,
        LockingCapSupport lockingCapSupport,
        BtcBlockStoreWithCache.Factory btcBlockStoreFactory,
        BootstrapWindow bootstrapWindow) {
        this.bootstrapWindow = bootstrapWindow;
        this.host = host;
        this.provider = provider;
        this.bridgeConstants = bridgeConstants;
        this.networkParameters = bridgeConstants.getBtcParams();
        this.eventLogger = eventLogger;
        this.btcLockSenderProvider = btcLockSenderProvider;
        this.peginInstructionsProvider = peginInstructionsProvider;
        this.btcContext = btcContext;
        this.feePerKbSupport = feePerKbSupport;
        this.federationSupport = federationSupport;
        this.lockingCapSupport = lockingCapSupport;
        this.btcBlockStoreFactory = btcBlockStoreFactory;
    }

    /**
     * What the peg asks of a peg-in at this height. Less while the chain is bootstrapping, so that the
     * peg-in that seeds it does not have to be as large as the ones it will later carry.
     */
    public Coin getMinimumPeginTxValue() {
        return bootstrapWindow.isOpenAt(host.blockNumber())
            ? bridgeConstants.getBootstrapMinimumPeginTxValue()
            : bridgeConstants.getMinimumPeginTxValue();
    }

    @VisibleForTesting
    InputStream getCheckPoints() {
        String resourceName = "/rskbitcoincheckpoints/" + networkParameters.getId() + ".checkpoints";
        InputStream checkpoints = BridgeSupport.class.getResourceAsStream(resourceName);
        logger.debug("[getCheckPoints] Looking for checkpoint {}. Found? {}", resourceName, checkpoints != null);
        if (checkpoints == null) {
            // If we don't have a custom checkpoints file, try to use bitcoinj's default checkpoints for that network
            checkpoints = BridgeSupport.class.getResourceAsStream("/" + networkParameters.getId() + ".checkpoints");
        }
        return checkpoints;
    }

    public void save() {
        provider.save();
        feePerKbSupport.save();
        federationSupport.save();
        lockingCapSupport.save();
    }

    /**
     * Receives an array of serialized Bitcoin block headers and adds them to the internal BlockChain structure.
     * @param headers The bitcoin headers
     */
    public void receiveHeaders(BtcBlock[] headers) throws IOException, BlockStoreException {
        if (headers.length > 0) {
            logger.debug("Received {} headers. First {}, last {}.", headers.length, headers[0].getHash(), headers[headers.length - 1].getHash());
        } else {
            logger.warn("Received 0 headers");
        }

        Context.propagate(btcContext);
        this.ensureBtcBlockChain();
        for (BtcBlock header : headers) {
            try {
                btcBlockChain.add(header);
            } catch (Exception e) {
                // If we try to add an orphan header bitcoinj throws an exception
                // This catches that case and any other exception that may be thrown
                logger.warn("Exception adding btc header {}", header.getHash(), e);
            }
        }
    }

    /**
     * Receives only one header of serialized Bitcoin block headers and adds them to the internal BlockChain structure.
     * @param header The bitcoin headers
     */
    public Integer receiveHeader(BtcBlock header) throws IOException, BlockStoreException {
        Context.propagate(btcContext);
        this.ensureBtcBlockChain();

        if (btcBlockStore.get(header.getHash()) != null) {
            return RECEIVE_HEADER_BLOCK_PREVIOUSLY_SAVED;
        }

        long diffTimeStamp = bridgeConstants.getMinSecondsBetweenCallsToReceiveHeader();

        long currentTimeStamp = host.blockTimestamp(); //in seconds
        Optional<Long> optionalLastTimeStamp = provider.getReceiveHeadersLastTimestamp();
        if (optionalLastTimeStamp.isPresent() && (currentTimeStamp - optionalLastTimeStamp.get() < diffTimeStamp)) {
            logger.warn("Receive header last TimeStamp less than {} milliseconds", diffTimeStamp);
            return RECEIVE_HEADER_CALLED_TOO_SOON;
        }

        //Depth
        StoredBlock previousBlock = btcBlockStore.get(header.getPrevBlockHash());
        if (previousBlock == null) {
            return RECEIVE_HEADER_CANT_FOUND_PREVIOUS_BLOCK;
        }

        // height of best chain - height of current header block greater than maximum depth accepted
        if ((getBtcBlockchainBestChainHeight() - (previousBlock.getHeight() + 1)) > bridgeConstants.getMaxDepthBlockchainAccepted()) {
            return RECEIVE_HEADER_BLOCK_TOO_OLD;
        }

        try {
            btcBlockChain.add(header);
        } catch (Exception e) {
            // If we try to add an orphan header bitcoinj throws an exception
            // This catches that case and any other exception that may be thrown
            logger.warn("Exception adding btc header {}", header.getHash(), e);
            return RECEIVE_HEADER_UNEXPECTED_EXCEPTION;
        }
        provider.setReceiveHeadersLastTimestamp(currentTimeStamp);
        return 0;
    }

    /**
     * Get the wallet for the currently active federation
     * @return A BTC wallet for the currently active federation
     *
     */
    public Wallet getActiveFederationWallet() {
        Federation federation = getActiveFederation();
        List<UTXO> utxos = federationSupport.getActiveFederationBtcUTXOs();

        return BridgeUtils.getFederationSpendWallet(
            btcContext,
            federation,
            utxos
        );
    }

    /**
     * Get the wallet for the currently retiring federation
     * or null if there's currently no retiring federation
     * @return A BTC wallet for the currently active federation
     *
     */
    protected Wallet getRetiringFederationWallet() {
        List<UTXO> retiringFederationBtcUTXOs = federationSupport.getRetiringFederationBtcUTXOs();
        return getRetiringFederationWallet(retiringFederationBtcUTXOs.size());
    }

    private Wallet getRetiringFederationWallet(int utxosSizeLimit) {
        Federation federation = getRetiringFederation();
        if (federation == null) {
            logger.debug("[getRetiringFederationWallet] No retiring federation found");
            return null;
        }

        List<UTXO> utxos = federationSupport.getRetiringFederationBtcUTXOs();
        if (utxos.size() > utxosSizeLimit) {
            logger.debug("[getRetiringFederationWallet] Going to limit the amount of UTXOs to {}", utxosSizeLimit);
            utxos = utxos.subList(0, utxosSizeLimit);
        }

        logger.debug("[getRetiringFederationWallet] Fetching retiring federation spend wallet");
        return BridgeUtils.getFederationSpendWallet(
            btcContext,
            federation,
            utxos
        );
    }

    /**
     * Get the wallet for the currently live federations
     * but limited to a specific list of UTXOs
     * @return A BTC wallet for the currently live federation(s)
     * limited to the given list of UTXOs
     *
     */
    public Wallet getUTXOBasedWalletForLiveFederations(List<UTXO> utxos) {
        return BridgeUtils.getFederationsSpendWallet(
            btcContext,
            federationSupport.getLiveFederations(),
            utxos
        );
    }

    /**
     * Get a no spend wallet for the currently live federations
     * @return A no spend BTC wallet for the currently live federation(s)
     *
     */
    public Wallet getNoSpendWalletForLiveFederations() {
        return BridgeUtils.getFederationsNoSpendWallet(
            btcContext,
            federationSupport.getLiveFederations()
        );
    }

    /**
     * In case of a peg-in tx: Transfers some RBTCs to the sender of the btc tx and keeps track of the new UTXOs available for spending.
     * In case of a peg-out tx: Keeps track of the change UTXOs, now available for spending.
     * @param rskTx The RSK transaction
     * @param btcTxSerialized The raw BTC tx
     * @param height The height of the BTC block that contains the tx
     * @param pmtSerialized The raw partial Merkle tree
     * @throws BlockStoreException If there's an error while executing validations
     * @throws IOException If there's an error while processing the tx
     */
    public void registerBtcTransaction(
        CallContext rskTx,
        byte[] btcTxSerialized,
        int height,
        byte[] pmtSerialized
    ) throws IOException, BlockStoreException, BridgeIllegalArgumentException {
        Context.propagate(btcContext);
        Hash rskTxHash = rskTx.getHash();
        Sha256Hash btcTxHash = BtcTransactionFormatUtils.calculateBtcTxHash(btcTxSerialized);
        logger.debug("[registerBtcTransaction][rsk tx {}] Processing btc tx {}", rskTxHash, btcTxHash);

        try {
            // Check the tx was not already processed
            if (isAlreadyBtcTxHashProcessed(btcTxHash)) {
                throw new RegisterBtcTransactionException("Transaction already processed");
            }

            // Validations for register
            if (!validationsForRegisterBtcTransaction(btcTxHash, height, pmtSerialized, btcTxSerialized)) {
                throw new RegisterBtcTransactionException("Could not validate transaction");
            }

            BtcTransaction btcTx = new BtcTransaction(networkParameters, btcTxSerialized);
            btcTx.verify();
            logger.debug("[registerBtcTransaction][rsk tx {}] Btc tx hash without witness {}", rskTxHash, btcTx.getHash(false));

            // Check again that the tx was not already processed but making sure to use the txid (no witness)
            if (isAlreadyBtcTxHashProcessed(btcTx.getHash(false))) {
                throw new RegisterBtcTransactionException("Transaction already processed");
            }

            FederationContext federationContext = federationSupport.getFederationContext();
            PegTxType pegTxType = PegUtils.getTransactionType(
                provider,
                bridgeConstants,
                federationContext,
                btcTx
            );

            logger.info("[registerBtcTransaction][btctx: {}] This is a {} transaction type", btcTx.getHash(), pegTxType);
            switch (pegTxType) {
                case PEGIN -> registerPegIn(btcTx, rskTxHash);
                case PEGOUT_OR_MIGRATION -> registerNewUtxos(btcTx);
                case SVP_FUND_TX -> registerSvpFundTx(btcTx);
                case SVP_SPEND_TX -> registerSvpSpendTx(btcTx);
                case UNKNOWN -> logger.warn("[registerBtcTransaction] Unknown peg tx type won't be registered.");
            }
        } catch (RegisterBtcTransactionException e) {
            logger.warn(
                "[registerBtcTransaction][rsk tx {}] Could not register transaction {}. Message: {}",
                rskTxHash,
                btcTxHash,
                e.getMessage()
            );
        }
    }

    private void registerSvpFundTx(BtcTransaction btcTx) throws IOException {
        registerNewUtxos(btcTx); // Need to register the change UTXO

        // If the SVP validation period is over, SVP related values should be cleared in the next call to updateCollections
        // In that case, the fundTx will be identified as a regular peg-out tx and processed via #registerPegoutOrMigration
        // This covers the case when the fundTx is registered between the validation period end and the next call to updateCollections
        if (isSvpOngoing()) {
            updateSvpFundTransactionValues(btcTx);
        }
    }

    private void registerSvpSpendTx(BtcTransaction btcTx) throws IOException {
        registerNewUtxos(btcTx);
        provider.clearSvpSpendTxHashUnsigned();

        logger.info("[registerSvpSpendTx] Going to commit the proposed federation.");
        federationSupport.commitProposedFederation();
    }

    private void updateSvpFundTransactionValues(BtcTransaction transaction) {
        logger.info(
            "[updateSvpFundTransactionValues] Transaction {} (wtxid:{}) is the svp fund transaction. Going to update its values",
            transaction.getHash(),
            transaction.getHash(true)
        );

        provider.setSvpFundTxSigned(transaction);
        provider.clearSvpFundTxHashUnsigned();
    }

    @VisibleForTesting
    BtcBlockStoreWithCache getBtcBlockStore() {
        return btcBlockStore;
    }

    protected void registerPegIn(
        BtcTransaction btcTx,
        Hash rskTxHash
    ) throws IOException, RegisterBtcTransactionException {
        final String METHOD_NAME = "registerPegIn";

        Coin totalAmount = computeTotalAmountSent(btcTx);
        logger.debug("[{}] Total amount sent: {}", METHOD_NAME, totalAmount);

        PeginInformation peginInformation = new PeginInformation(
            btcLockSenderProvider,
            peginInstructionsProvider
        );
        Coin minimumPeginTxValue = getMinimumPeginTxValue();
        Wallet fedWallet = getNoSpendWalletForLiveFederations();
        PeginEvaluationResult peginEvaluationResult = PegUtils.evaluatePegin(
            btcTx,
            peginInformation,
            minimumPeginTxValue,
            fedWallet
        );

        PeginProcessAction peginProcessAction = peginEvaluationResult.getPeginProcessAction();

        switch (peginProcessAction) {
            case REGISTER -> {
                logger.debug("[{}] Peg-in is valid, going to register", METHOD_NAME);
                executePegIn(btcTx, peginInformation, totalAmount);
            }
            case REFUND -> handleRefundablePegin(btcTx, rskTxHash, peginEvaluationResult, peginInformation.getBtcRefundAddress());
            case NO_REFUND -> handleNonRefundablePegin(btcTx, peginInformation.getProtocolVersion(), peginEvaluationResult);
        }
    }

    private void handleRefundablePegin(
        BtcTransaction btcTx,
        Hash rskTxHash,
        PeginEvaluationResult peginEvaluationResult,
        Address btcRefundAddress
    ) throws IOException {

        RejectedPeginReason rejectedPeginReason = peginEvaluationResult.getRejectedPeginReason()
            .orElseThrow(() -> {
                // This flow should never be reached. There should always be a rejected pegin reason.
                String message = "Invalid state. No rejected reason was returned for an invalid pegin.";
                logger.error("[{handleRefundablePegin}] {}", message);
                return new IllegalStateException(message);
            });

        logger.debug("[{handleRefundablePegin}] Rejected peg-in, reason {}", rejectedPeginReason);
        eventLogger.logRejectedPegin(btcTx, rejectedPeginReason);

        logger.debug("[{handleRefundablePegin}] Refunding to address {} ", btcRefundAddress);
        Coin totalAmount = computeTotalAmountSent(btcTx);
        generateRejectionRelease(btcTx, btcRefundAddress, rskTxHash, totalAmount);
        markTxAsProcessed(btcTx);
    }

    private void handleNonRefundablePegin(
        BtcTransaction btcTx,
        int protocolVersion,
        PeginEvaluationResult peginEvaluationResult
    ) throws IOException {
        RejectedPeginReason rejectedPeginReason = peginEvaluationResult.getRejectedPeginReason()
            .orElseThrow(() -> {
                // This flow should never be reached. There should always be a rejected pegin reason.
                String message = "Invalid state. No rejected reason was returned for an invalid pegin.";
                logger.error("[{handleNonRefundablePegin}] {}", message);
                return new IllegalStateException(message);
            });

        logger.debug("[{handleNonRefundablePegin}] Rejected peg-in, reason {}",
            rejectedPeginReason);
        eventLogger.logRejectedPegin(btcTx, rejectedPeginReason);

        NonRefundablePeginReason nonRefundablePeginReason =
            switch (rejectedPeginReason) {
                case INVALID_AMOUNT -> NonRefundablePeginReason.INVALID_AMOUNT;
                case LEGACY_PEGIN_MULTISIG_SENDER -> NonRefundablePeginReason.OUTPUTS_SENT_TO_DIFFERENT_TYPES_OF_FEDS; // Only reason for a legacy pegin multisig sender not being refunded is if it has outputs to different types of feds
                case LEGACY_PEGIN_UNDETERMINED_SENDER, PEGIN_V1_INVALID_PAYLOAD ->
                    protocolVersion == 1 ? NonRefundablePeginReason.PEGIN_V1_REFUND_ADDRESS_NOT_SET
                        : NonRefundablePeginReason.LEGACY_PEGIN_UNDETERMINED_SENDER;
                default -> throw new IllegalStateException("Unexpected value: " + rejectedPeginReason);
            };

        logger.debug("[handleNonRefundablePegin] Nonrefundable tx {}. Reason {}",
            btcTx.getHash(),
            nonRefundablePeginReason
        );
        eventLogger.logNonRefundablePegin(btcTx, nonRefundablePeginReason);

        // Rejected peg-ins are marked as processed
        markTxAsProcessed(btcTx);
    }

    private void executePegIn(BtcTransaction btcTx, PeginInformation peginInformation, Coin amount) throws IOException {
        org.hyperledger.besu.datatypes.Address rskDestinationAddress = peginInformation.getRskDestinationAddress();
        Address senderBtcAddress = peginInformation.getSenderBtcAddress();
        TxSenderAddressType senderBtcAddressType = peginInformation.getSenderBtcAddressType();
        int protocolVersion = peginInformation.getProtocolVersion();
        Wei amountInWeis = Weis.fromSatoshis(amount);

        logger.debug("[executePegIn] [btcTx:{}] Is a peg-in from a {} sender", btcTx.getHash(), senderBtcAddressType);
        this.transferTo(peginInformation.getRskDestinationAddress(), amountInWeis);
        logger.info(
            "[executePegIn] Transferring from BTC address {}. RSK address: {}. Amount: {}",
            senderBtcAddress,
            rskDestinationAddress,
            amountInWeis
        );

        eventLogger.logPeginBtc(rskDestinationAddress, btcTx, amount, protocolVersion);

        // Save UTXOs from the federation(s) only if we actually locked the funds
        registerNewUtxos(btcTx);
    }

    private void markTxAsProcessed(BtcTransaction btcTx) throws IOException {
        // Mark tx as processed on this block (and use the txid without the witness)
        long rskHeight = host.blockNumber();
        provider.setHeightBtcTxhashAlreadyProcessed(btcTx.getHash(false), rskHeight);
        logger.debug(
            "[markTxAsProcessed] Mark btc transaction {} (wtxid: {}) as processed at height {}",
            btcTx.getHash(),
            btcTx.getHash(true),
            rskHeight
        );
    }

    /**
     * Internal method to transfer RSK to an RSK account
     * It also produce the appropiate internal transaction subtrace if needed
     *
     * @param receiver  address that receives the amount
     * @param amount    amount to transfer
     */
    private void transferTo(org.hyperledger.besu.datatypes.Address receiver, Wei amount) {
        host.transfer(
                BridgeAddresses.BRIDGE,
                receiver,
                amount
        );

        logger.info("Transferred {} weis to {}", amount, receiver);
    }

    /*
    Add the btcTx outputs that send btc to the federation(s) to the UTXO list,
    so they can be used as inputs in future peg-out transactions.
    Finally, mark the btcTx as processed.
     */
    private void registerNewUtxos(BtcTransaction btcTx) throws IOException {
        // Outputs to the active federation
        Wallet activeFederationWallet = getActiveFederationWallet();
        List<TransactionOutput> outputsToTheActiveFederation = btcTx.getWalletOutputs(
            activeFederationWallet
        );
        for (TransactionOutput output : outputsToTheActiveFederation) {
            UTXO utxo = new UTXO(
                btcTx.getHash(),
                output.getIndex(),
                output.getValue(),
                0,
                btcTx.isCoinBase(),
                output.getScriptPubKey()
            );
            federationSupport.getActiveFederationBtcUTXOs().add(utxo);
        }
        logger.debug("[registerNewUtxos] Registered {} UTXOs sent to the active federation", outputsToTheActiveFederation.size());

        // Outputs to the retiring federation (if any)
        Wallet retiringFederationWallet = getRetiringFederationWallet();
        if (retiringFederationWallet != null) {
            List<TransactionOutput> outputsToTheRetiringFederation = btcTx.getWalletOutputs(retiringFederationWallet);
            for (TransactionOutput output : outputsToTheRetiringFederation) {
                UTXO utxo = new UTXO(
                    btcTx.getHash(),
                    output.getIndex(),
                    output.getValue(),
                    0,
                    btcTx.isCoinBase(),
                    output.getScriptPubKey()
                );
                federationSupport.getRetiringFederationBtcUTXOs().add(utxo);
            }
            logger.debug("[registerNewUtxos] Registered {} UTXOs sent to the retiring federation", outputsToTheRetiringFederation.size());
        }

        markTxAsProcessed(btcTx);
        logger.info("[registerNewUtxos] BTC Tx {} (wtxid: {}) processed in RSK", btcTx.getHash(), btcTx.getHash(true));
    }

    /**
     * Initiates the process of sending coins back to BTC.
     * This is the default contract method.
     * The funds will be sent to the bitcoin address controlled by the private key that signed the rsk tx.
     * The amount sent to the bridge in this tx will be the amount sent in the btc network minus fees.
     * @param rskTx The rsk tx being executed.
     * @throws IOException If there's an error while processing the release request.
     */
    public void releaseBtc(CallContext rskTx) throws IOException {
        final Wei pegoutValueInWeis = rskTx.getValue();
        final org.hyperledger.besu.datatypes.Address senderAddress = rskTx.getSender();
        logger.debug(
            "[releaseBtc] Releasing {} weis from RSK address {} in tx {}",
            pegoutValueInWeis,
            senderAddress,
            rskTx.getHash()
        );

        // Peg-out from a smart contract not allowed since it's not possible to derive a BTC address from it
        if (rskTx.isFromContract()) {
            logger.trace(
                "[releaseBtc] Contract {} tried to release funds. Release is just allowed from EOA",
                senderAddress
            );
            emitRejectEvent(pegoutValueInWeis, senderAddress, RejectedPegoutReason.CALLER_CONTRACT);
            return;
        }

        Context.propagate(btcContext);
        Address btcDestinationAddress = BridgeUtils.recoverBtcAddressFromEthTransaction(rskTx, networkParameters);
        logger.debug("[releaseBtc] BTC destination address: {}", btcDestinationAddress);

        requestRelease(btcDestinationAddress, pegoutValueInWeis, rskTx);
    }

    private void refundAndEmitRejectEvent(
        Wei releaseRequestedValueInWeis,
        org.hyperledger.besu.datatypes.Address senderAddress,
        RejectedPegoutReason reason
    ) {
        logger.trace(
            "[refundAndEmitRejectEvent] Executing a refund of {} weis to {}. Reason: {}",
            releaseRequestedValueInWeis,
            senderAddress,
            reason
        );

        // The refund is the value requested, not its rounding to satoshis
        host.transfer(
            BridgeAddresses.BRIDGE,
            senderAddress,
            releaseRequestedValueInWeis
        );
        emitRejectEvent(releaseRequestedValueInWeis, senderAddress, reason);
    }

    private void emitRejectEvent(Wei releaseRequestedValueInWeis, org.hyperledger.besu.datatypes.Address senderAddress, RejectedPegoutReason reason) {
        eventLogger.logReleaseBtcRequestRejected(senderAddress, releaseRequestedValueInWeis, reason);
    }

    /**
     * Creates a request for BTC release and
     * adds it to the request queue for it
     * to be processed later.
     *
     * @param destinationAddress the destination BTC address.
     * @param releaseRequestedValueInWeis the amount of RBTC requested to be released, represented in weis
     * @throws IOException if there is an error getting the release request queue from storage
     */
    private void requestRelease(Address destinationAddress, Wei releaseRequestedValueInWeis, CallContext rskTx) throws IOException {
        Coin valueToReleaseInSatoshis = Weis.toSatoshis(releaseRequestedValueInWeis);
        Optional<RejectedPegoutReason> optionalRejectedPegoutReason = Optional.empty();
        int pegoutSize = getRegularPegoutTxSize(getActiveFederation());
        Coin feePerKB = getFeePerKb();
        // The pegout transaction has a cost related to its size and the current feePerKB
        // The actual cost cannot be asserted exactly so the calculation is approximated
        // On top of this, the remainder after the fee should be enough for the user to be able to operate
        // For this, the calculation includes an additional percentage to assert for this
        Coin requireFundsForFee = feePerKB
            .multiply(pegoutSize) // times the size in bytes
            .divide(1000); // Get the s/b
        requireFundsForFee = requireFundsForFee
            .add(requireFundsForFee
                .times(bridgeConstants.getMinimumPegoutValuePercentageToReceiveAfterFee())
                .divide(100)
            ); // add the gap

        // The pegout releaseRequestedValueInWeis should be greater or equals than the max of these two values
        Coin minValue = Coin.valueOf(Math.max(bridgeConstants.getMinimumPegoutTxValue().value, requireFundsForFee.value));

        // The minimum is inclusive
        if (valueToReleaseInSatoshis.isLessThan(minValue)) {
            optionalRejectedPegoutReason = Optional.of(
                Objects.equals(minValue, requireFundsForFee) ?
                RejectedPegoutReason.FEE_ABOVE_VALUE:
                RejectedPegoutReason.LOW_AMOUNT
            );
        }

        if (optionalRejectedPegoutReason.isPresent()) {
            logger.warn(
                "[requestRelease] releaseBtc ignored. To {}. Tx {}. Value {} weis. Reason: {}",
                destinationAddress,
                rskTx,
                releaseRequestedValueInWeis,
                optionalRejectedPegoutReason.get()
            );
            refundAndEmitRejectEvent(
                releaseRequestedValueInWeis,
                rskTx.getSender(),
                optionalRejectedPegoutReason.get()
            );
        } else {
            provider.getReleaseRequestQueue().add(destinationAddress, valueToReleaseInSatoshis, rskTx.getHash());

            org.hyperledger.besu.datatypes.Address sender = rskTx.getSender();
            eventLogger.logReleaseBtcRequestReceived(
                sender,
                destinationAddress,
                releaseRequestedValueInWeis
            );
            logger.info(
                "[requestRelease] releaseBtc successful to {}. Tx {}. Value {} weis.",
                destinationAddress,
                rskTx,
                releaseRequestedValueInWeis
            );
        }
    }

    /**
     * Executed every now and then.
     * Performs a few tasks: processing of any pending btc funds
     * migrations from retiring federations;
     * processing of any outstanding pegout requests; and
     * processing of any outstanding confirmed pegouts.
     * @throws IOException
     * @param rskTx current RSK transaction
     */
    public void updateCollections(CallContext rskTx) throws IOException {
        Context.propagate(btcContext);

        logUpdateCollections(rskTx);

        processFundsMigration(rskTx);

        processPegoutRequests(rskTx);

        processConfirmedPegouts(rskTx);

        updateFederationCreationBlockHeights();

        updateSvpState(rskTx);
    }

    private void logUpdateCollections(CallContext rskTx) {
        org.hyperledger.besu.datatypes.Address sender = rskTx.getSender();
        eventLogger.logUpdateCollections(sender);
    }

    private void updateSvpState(CallContext rskTx) {
        Optional<Federation> proposedFederationOpt = federationSupport.getProposedFederation();
        if (proposedFederationOpt.isEmpty()) {
            return;
        }

        // if the proposed federation exists and the validation period ended,
        // we can conclude that the svp failed
        Federation proposedFederation = proposedFederationOpt.get();
        if (!isSvpOngoing()) {
            processSvpFailure(proposedFederation);
            return;
        }

        Hash rskTxHash = rskTx.getHash();

        if (shouldCreateAndProcessSvpFundTransaction()) {
            logger.info("[updateSvpState] No svp values were found, so fund tx creation will be processed.");
            processSvpFundTransactionUnsigned(rskTxHash, proposedFederation);
        }

        // if the fund tx signed is present, then the fund transaction change was registered,
        // meaning we can create the spend tx.
        Optional<BtcTransaction> svpFundTxSigned = provider.getSvpFundTxSigned();
        if (svpFundTxSigned.isPresent()) {
            logger.info(
                "[updateSvpState] Fund tx signed was found, so spend tx creation will be processed."
            );
            processSvpSpendTransactionUnsigned(rskTxHash, proposedFederation, svpFundTxSigned.get());
        }
    }

    private boolean shouldCreateAndProcessSvpFundTransaction() {
        // the fund tx will be created when the svp starts,
        // so we must ensure all svp values are clear to proceed with its creation
        Optional<Sha256Hash> svpFundTxHashUnsigned = provider.getSvpFundTxHashUnsigned();
        Optional<BtcTransaction> svpFundTxSigned = provider.getSvpFundTxSigned();
        Optional<Sha256Hash> svpSpendTxHashUnsigned = provider.getSvpSpendTxHashUnsigned(); // spendTxHash will be removed the last, after spendTxWFS, so is enough checking just this value

        return svpFundTxHashUnsigned.isEmpty()
            && svpFundTxSigned.isEmpty()
            && svpSpendTxHashUnsigned.isEmpty();
    }

    private void processSvpFailure(Federation proposedFederation) {
        logger.info(
            "[processSvpFailure] Proposed federation validation failed at block {}. SVP failure will be processed and Federation election will be allowed again.",
            host.blockNumber()
        );
        eventLogger.logCommitFederationFailure(host.blockNumber(), proposedFederation);
        allowFederationElectionAgain();
    }

    private void allowFederationElectionAgain() {
        federationSupport.clearProposedFederation();
        provider.clearSvpValues();
    }

    private boolean isSvpOngoing() {
        return federationSupport
            .getProposedFederation()
            .map(proposedFederation -> host.blockNumber() < proposedFederation.getCreationBlockNumber() +
                bridgeConstants.getFederationConstants().getValidationPeriodDurationInBlocks()
            )
            .orElse(false);
    }

    private void processSvpFundTransactionUnsigned(Hash rskTxHash, Federation proposedFederation) {
        ReleaseTransactionBuilder.BuildResult svpFundTransactionUnsignedBuildResult = buildSvpFundTransaction(proposedFederation);
        ReleaseTransactionBuilder.Response responseCode = svpFundTransactionUnsignedBuildResult.getResponseCode();
        if (responseCode != ReleaseTransactionBuilder.Response.SUCCESS) {
            logger.warn("[processSvpFundTransactionUnsigned] Couldn't create svp fund transaction. Got {} response code", responseCode);
            return;
        }

        BtcTransaction svpFundTransactionUnsigned = svpFundTransactionUnsignedBuildResult.getBtcTx();
        provider.setSvpFundTxHashUnsigned(svpFundTransactionUnsigned.getHash());
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        List<UTXO> utxosToUse = federationSupport.getActiveFederationBtcUTXOs();
        // one output to proposed fed, one output to flyover proposed fed
        Coin totalValueSentToProposedFederation = bridgeConstants.getSvpFundTxOutputsValue().multiply(2);
        settleReleaseRequest(utxosToUse, pegoutsWaitingForConfirmations, svpFundTransactionUnsigned, rskTxHash, totalValueSentToProposedFederation);
    }

    private ReleaseTransactionBuilder.BuildResult buildSvpFundTransaction(Federation proposedFederation) {
        Wallet activeFederationWallet = getActiveFederationWallet();
        Federation activeFederation = getActiveFederation();
        ReleaseTransactionBuilder txBuilder = new ReleaseTransactionBuilder(
            btcContext.getParams(),
            activeFederationWallet,
            activeFederation.getFormatVersion(),
            activeFederation.getAddress(),
            getFeePerKb()
        );

        return txBuilder.buildSvpFundTransaction(
            proposedFederation,
            bridgeConstants.getProposedFederationFlyoverPrefix(),
            bridgeConstants.getSvpFundTxOutputsValue()
        );
    }

    private void processSvpSpendTransactionUnsigned(Hash rskTxHash, Federation proposedFederation, BtcTransaction svpFundTxSigned) {
        BtcTransaction svpSpendTransactionUnsigned;
        try {
            svpSpendTransactionUnsigned = createSvpSpendTransaction(svpFundTxSigned, proposedFederation);
        } catch (IllegalStateException e){
            logger.error("[processSvpSpendTransactionUnsigned] Error creating spend transaction {}", e.getMessage());
            return;
        }
        updateSvpSpendTransactionValues(rskTxHash, svpSpendTransactionUnsigned);

        Coin amountSentToActiveFed = svpSpendTransactionUnsigned.getOutput(0).getValue();
        logReleaseRequested(rskTxHash, svpSpendTransactionUnsigned, amountSentToActiveFed);
        processReleaseTransactionInfo(svpSpendTransactionUnsigned);
    }

    private BtcTransaction createSvpSpendTransaction(BtcTransaction svpFundTxSigned, Federation proposedFederation) throws IllegalStateException {
        BtcTransaction svpSpendTransaction = new BtcTransaction(networkParameters);
        svpSpendTransaction.setVersion(BTC_TX_VERSION_2);

        Script proposedFederationRedeemScript = proposedFederation.getRedeemScript();
        int proposedFederationFormatVersion = proposedFederation.getFormatVersion();

        TransactionOutput outputToProposedFed = searchForOutput(
            svpFundTxSigned.getOutputs(),
            proposedFederation.getP2SHScript()
        ).orElseThrow(() -> new IllegalStateException("[createSvpSpendTransaction] Output to proposed federation was not found in fund transaction."));
        svpSpendTransaction.addInput(outputToProposedFed);
        int proposedFederationInputIndex = 0;
        addSpendingFederationBaseScript(svpSpendTransaction, proposedFederationInputIndex, proposedFederationRedeemScript, proposedFederationFormatVersion);

        Script flyoverFederationRedeemScript = getFlyoverFederationRedeemScript(bridgeConstants.getProposedFederationFlyoverPrefix(), proposedFederationRedeemScript);
        Script flyoverOutputScript = getFlyoverFederationOutputScript(flyoverFederationRedeemScript, proposedFederation.getFormatVersion());
        TransactionOutput outputToFlyoverProposedFed = searchForOutput(
            svpFundTxSigned.getOutputs(),
            flyoverOutputScript
        ).orElseThrow(() -> new IllegalStateException("[createSvpSpendTransaction] Output to flyover proposed federation was not found in fund transaction."));
        svpSpendTransaction.addInput(outputToFlyoverProposedFed);
        int flyoverFederationInputIndex = 1;
        addSpendingFederationBaseScript(svpSpendTransaction, flyoverFederationInputIndex, flyoverFederationRedeemScript, proposedFederation.getFormatVersion());

        Coin valueSentToProposedFed = outputToProposedFed.getValue();
        Coin valueSentToFlyoverProposedFed = outputToFlyoverProposedFed.getValue();

        Coin valueToSend = valueSentToProposedFed
            .plus(valueSentToFlyoverProposedFed)
            .minus(calculateSvpSpendTxFees(proposedFederation));

        svpSpendTransaction.addOutput(
            valueToSend,
            federationSupport.getActiveFederationAddress()
        );

        return svpSpendTransaction;
    }

    private Coin calculateSvpSpendTxFees(Federation proposedFederation) {
        int svpSpendTransactionSize = calculatePegoutTxSize(proposedFederation, 2, 1);
        long svpSpendTransactionBackedUpSize = svpSpendTransactionSize * 12L / 10L; // just to be sure the fees sent will be enough

        return feePerKbSupport.getFeePerKb()
            .multiply(svpSpendTransactionBackedUpSize)
            .divide(1000);
    }

    private void updateSvpSpendTransactionValues(Hash rskTxHash, BtcTransaction svpSpendTransactionUnsigned) {
        provider.setSvpSpendTxHashUnsigned(svpSpendTransactionUnsigned.getHash());
        provider.setSvpSpendTxWaitingForSignatures(
            new AbstractMap.SimpleEntry<>(rskTxHash, svpSpendTransactionUnsigned)
        );

        provider.setSvpFundTxSigned(null);
    }

    protected void updateFederationCreationBlockHeights() {
        federationSupport.updateFederationCreationBlockHeights();
    }

    private void processFundsMigration(CallContext rskTx) throws IOException {
        Wallet retiringFederationWallet = getRetiringFederationWallet(bridgeConstants.getMaxInputsPerPegoutTransaction());

        List<UTXO> availableUTXOs = federationSupport.getRetiringFederationBtcUTXOs();
        Federation activeFederation = getActiveFederation();

        if (federationIsInMigrationAge(activeFederation)) {
            long federationAge = host.blockNumber() - activeFederation.getCreationBlockNumber();
            logger.trace("[processFundsMigration] Active federation (age={}) is in migration age.", federationAge);
            if (hasMinimumFundsToMigrate(retiringFederationWallet)){
                Coin retiringFederationBalance = retiringFederationWallet.getBalance();
                String retiringFederationBalanceInFriendlyFormat = retiringFederationBalance.toFriendlyString();
                logger.info(
                    "[processFundsMigration] Retiring federation has funds to migrate: {}.",
                    retiringFederationBalanceInFriendlyFormat
                );

                migrateFunds(
                    rskTx.getHash(),
                    retiringFederationWallet,
                    activeFederation.getAddress(),
                    availableUTXOs
                );
            }
        }

        if (retiringFederationWallet != null && federationIsPastMigrationAge(activeFederation)) {
            if (retiringFederationWallet.getBalance().isGreaterThan(Coin.ZERO)) {
                Coin retiringFederationBalance = retiringFederationWallet.getBalance();
                String retiringFederationBalanceInFriendlyFormat = retiringFederationBalance.toFriendlyString();
                logger.info(
                    "[processFundsMigration] Federation is past migration age and will try to migrate remaining balance: {}.",
                    retiringFederationBalanceInFriendlyFormat
                );

                try {
                    migrateFunds(
                        rskTx.getHash(),
                        retiringFederationWallet,
                        activeFederation.getAddress(),
                        availableUTXOs
                    );
                } catch (Exception e) {
                    logger.error(
                        "[processFundsMigration] Unable to complete retiring federation migration. Balance left: {} in {}",
                        retiringFederationWallet.getBalance().toFriendlyString(),
                        getRetiringFederationAddress()
                    );
                    logger.error("[panic] {} {}", "updateCollection", "Unable to complete retiring federation migration.");
                }
            }

            logger.info(
                "[processFundsMigration] Retiring federation migration finished. Available UTXOs left: {}.",
                availableUTXOs.size()
            );
            federationSupport.clearRetiredFederation();
        }
    }

    private boolean federationIsInMigrationAge(Federation federation) {
        FederationConstants federationConstants = bridgeConstants.getFederationConstants();

        long federationActivationAge = federationConstants.getFederationActivationAge();
        long federationAge = host.blockNumber() - federation.getCreationBlockNumber();
        long ageBegin = federationActivationAge + federationConstants.getFundsMigrationAgeSinceActivationBegin();
        long ageEnd = federationActivationAge + federationConstants.getFundsMigrationAgeSinceActivationEnd();

        return federationAge > ageBegin && federationAge < ageEnd;
    }

    private boolean federationIsPastMigrationAge(Federation federation) {
        FederationConstants federationConstants = bridgeConstants.getFederationConstants();

        long federationAge = host.blockNumber() - federation.getCreationBlockNumber();
        long ageEnd = federationConstants.getFederationActivationAge() +
            federationConstants.getFundsMigrationAgeSinceActivationEnd();

        return federationAge >= ageEnd;
    }

    private boolean hasMinimumFundsToMigrate(@Nullable Wallet retiringFederationWallet) {
        // This value is set according to the average 500 bytes transaction size
        Coin minimumFundsToMigrate = getFeePerKb().divide(2);
        return retiringFederationWallet != null
                && retiringFederationWallet.getBalance().isGreaterThan(minimumFundsToMigrate);
    }

    private void migrateFunds(
        Hash rskTxHash,
        Wallet retiringFederationWallet,
        Address activeFederationAddress,
        List<UTXO> utxosToUse
    ) throws IOException {

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();
        Pair<BtcTransaction, List<UTXO>> createResult = createMigrationTransaction(retiringFederationWallet, activeFederationAddress);
        BtcTransaction migrationTransaction = createResult.getLeft();
        List<UTXO> selectedUTXOs = createResult.getRight();

        logger.debug(
            "[migrateFunds] consumed {} UTXOs.",
            selectedUTXOs.size()
        );

        Coin amountMigrated = selectedUTXOs.stream()
            .map(UTXO::getValue)
            .reduce(Coin.ZERO, Coin::add);

        settleReleaseRequest(utxosToUse, pegoutsWaitingForConfirmations, migrationTransaction, rskTxHash, amountMigrated);
    }

    /**
     * Processes the current btc release request queue
     * and tries to build btc transactions using (and marking as spent)
     * the current active federation's utxos.
     * Newly created btc transactions are added to the btc release tx set,
     * and failed attempts are kept in the release queue for future
     * processing.
     *
     * @param rskTx
     */
    private void processPegoutRequests(CallContext rskTx) {
        final Wallet activeFederationWallet = getActiveFederationWallet();
        final ReleaseRequestQueue pegoutRequests = provider.getReleaseRequestQueue();
        final List<UTXO> availableUTXOs = federationSupport.getActiveFederationBtcUTXOs();
        final PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        // Pegouts are attempted using the currently active federation.
        int activeFederationFormatVersion = getActiveFederation().getFormatVersion();
        final ReleaseTransactionBuilder txBuilder = new ReleaseTransactionBuilder(
                btcContext.getParams(),
                activeFederationWallet,
                activeFederationFormatVersion,
                getActiveFederationAddress(),
                getFeePerKb()
        );

        processPegoutsInBatch(pegoutRequests, txBuilder, availableUTXOs, pegoutsWaitingForConfirmations, activeFederationWallet, rskTx);
    }

    private void settleReleaseRequest(List<UTXO> utxosToUse, PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations, BtcTransaction releaseTransaction, Hash releaseCreationTxHash, Coin requestedAmount) {
        removeSpentUtxos(utxosToUse, releaseTransaction);
        addPegoutToPegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations, releaseTransaction, releaseCreationTxHash);
        savePegoutTxSigHash(releaseTransaction);
        logReleaseRequested(releaseCreationTxHash, releaseTransaction, requestedAmount);
        processReleaseTransactionInfo(releaseTransaction);
    }

    private void removeSpentUtxos(List<UTXO> utxosToUse, BtcTransaction releaseTx) {
        List<UTXO> utxosToRemove = utxosToUse.stream()
            .filter(utxo -> releaseTx.getInputs().stream().anyMatch(input ->
                input.getOutpoint().getHash().equals(utxo.getHash()) && input.getOutpoint().getIndex() == utxo.getIndex())
            ).toList();

        logger.debug("[removeSpentUtxos] Used {} UTXOs for this release", utxosToRemove.size());

        utxosToUse.removeAll(utxosToRemove);
    }

    private void addPegoutToPegoutsWaitingForConfirmations(PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations, BtcTransaction pegoutTransaction, Hash releaseCreationTxHash) {
        long rskExecutionBlockNumber = host.blockNumber();
        pegoutsWaitingForConfirmations.add(pegoutTransaction, rskExecutionBlockNumber, releaseCreationTxHash);
    }

    private void savePegoutTxSigHash(BtcTransaction pegoutTx) {
        Optional<Sha256Hash> pegoutTxSigHash = getSigHashForPegoutIndex(pegoutTx);
        if (pegoutTxSigHash.isEmpty()){
            throw new IllegalStateException(String.format("SigHash could not be obtained from btc tx %s", pegoutTx.getHash()));
        }
        provider.setPegoutTxSigHash(pegoutTxSigHash.get());
    }

    private void logReleaseRequested(Hash releaseCreationTxHash, BtcTransaction pegoutTransaction, Coin requestedAmount) {
        // A pegout without a creation hash gets no event
        if (isNull(releaseCreationTxHash)) {
            return;
        }

        logger.debug(
            "[logReleaseRequested] release requested. rskTXHash: {}, btcTxHash: {}, amount: {}",
            releaseCreationTxHash, pegoutTransaction.getHash(), requestedAmount
        );

        byte[] rskTxHashSerialized = releaseCreationTxHash.getBytes().toArrayUnsafe();
        eventLogger.logReleaseBtcRequested(rskTxHashSerialized, pegoutTransaction, requestedAmount);
    }

    private void processReleaseTransactionInfo(BtcTransaction pegoutTransaction) {
        Sha256Hash pegoutTransactionHash = pegoutTransaction.getHash();
        List<Coin> outpointsValues = extractOutpointValues(pegoutTransaction);

        eventLogger.logPegoutTransactionCreated(pegoutTransactionHash, outpointsValues);
        provider.setReleaseOutpointsValues(pegoutTransactionHash, outpointsValues);
    }

    private void processPegoutsInBatch(
        ReleaseRequestQueue pegoutRequests,
        ReleaseTransactionBuilder txBuilder,
        List<UTXO> utxosToUse,
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations,
        Wallet wallet,
        CallContext rskTx
    ) {
        long currentBlockNumber = host.blockNumber();
        long nextPegoutCreationBlockNumber = getNextPegoutCreationBlockNumber();

        if (currentBlockNumber < nextPegoutCreationBlockNumber) {
            return;
        }

        List<ReleaseRequestQueue.Entry> pegoutEntries = pegoutRequests.getEntries();
        Coin totalPegoutValue = pegoutEntries
            .stream()
            .map(ReleaseRequestQueue.Entry::getAmount)
            .reduce(Coin.ZERO, Coin::add);

        if (wallet.getBalance().isLessThan(totalPegoutValue)) {
            logger.warn("[processPegoutsInBatch] wallet balance {} is less than the totalPegoutValue {}", wallet.getBalance(), totalPegoutValue);
            return;
        }

        if (!pegoutEntries.isEmpty()) {
            logger.info("[processPegoutsInBatch] going to create a batched pegout transaction for {} requests, total amount {}", pegoutEntries.size(), totalPegoutValue);
            ReleaseTransactionBuilder.BuildResult result = txBuilder.buildBatchedPegouts(pegoutEntries);

            while (pegoutEntries.size() > 1 && result.getResponseCode() == ReleaseTransactionBuilder.Response.EXCEED_MAX_TRANSACTION_SIZE) {
                logger.info("[processPegoutsInBatch] Max size exceeded, going to divide {} requests in half", pegoutEntries.size());
                int firstHalfSize = pegoutEntries.size() / 2;
                pegoutEntries = pegoutEntries.subList(0, firstHalfSize);
                result = txBuilder.buildBatchedPegouts(pegoutEntries);
            }

            if (result.getResponseCode() != ReleaseTransactionBuilder.Response.SUCCESS) {
                logger.warn(
                    "Couldn't build a pegout BTC tx for {} pending requests (total amount: {}), Reason: {}",
                    pegoutRequests.getEntries().size(),
                    totalPegoutValue,
                    result.getResponseCode());
                return;
            }

            logger.info(
                "[processPegoutsInBatch] pegouts processed with btcTx hash {} and response code {}",
                result.getBtcTx().getHash(), result.getResponseCode());

            BtcTransaction batchPegoutTransaction = result.getBtcTx();
            Hash batchPegoutCreationTxHash = rskTx.getHash();

            settleReleaseRequest(utxosToUse, pegoutsWaitingForConfirmations, batchPegoutTransaction, batchPegoutCreationTxHash, totalPegoutValue);

            // Remove batched requests from the queue after successfully batching pegouts
            pegoutRequests.removeEntries(pegoutEntries);

            eventLogger.logBatchPegoutCreated(batchPegoutTransaction.getHash(),
                pegoutEntries.stream().map(ReleaseRequestQueue.Entry::getRskTxHash).toList());

            adjustBalancesIfChangeOutputWasDust(batchPegoutTransaction, totalPegoutValue, wallet);
        }

        // set the next pegout creation block number when there are no pending pegout requests to be processed or they have been already processed
        if (pegoutRequests.getEntries().isEmpty()) {
            long nextPegoutHeight = currentBlockNumber + bridgeConstants.getNumberOfBlocksBetweenPegouts();
            provider.setNextPegoutHeight(nextPegoutHeight);
            logger.info("[processPegoutsInBatch] Next Pegout Height updated from {} to {}", currentBlockNumber, nextPegoutHeight);
        }
    }

    /**
     * Processes pegout waiting for confirmations.
     * It basically looks for pegout transactions with enough confirmations
     * and marks them as ready for signing as well as removes them
     * from the set.
     * @param rskTx the RSK transaction that is causing this processing.
     */
    private void processConfirmedPegouts(CallContext rskTx) {
        final Map<Hash, BtcTransaction> pegoutsWaitingForSignatures = provider.getPegoutsWaitingForSignatures();
        final PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        // TODO: (Ariel Mendelzon - 07/12/2017)
        // TODO: at the moment, there can only be one btc transaction
        // TODO: per rsk transaction in the pegoutsWaitingForSignatures
        // TODO: map, and the rest of the processing logic is
        // TODO: dependant upon this. That is the reason we
        // TODO: add only one btc transaction at a time
        // TODO: (at least at this stage).
        Optional<PegoutsWaitingForConfirmations.Entry> nextPegoutWithEnoughConfirmations = pegoutsWaitingForConfirmations
            .getNextPegoutWithEnoughConfirmations(
                host.blockNumber(),
                bridgeConstants.getRsk2BtcMinimumAcceptableConfirmations()
            );

        if (nextPegoutWithEnoughConfirmations.isEmpty()) {
            return;
        }

        PegoutsWaitingForConfirmations.Entry confirmedPegout = nextPegoutWithEnoughConfirmations.get();

        Hash txWaitingForSignatureKey = confirmedPegout.getPegoutCreationRskTxHash();
        /*
         This check aims to prevent confirmedPegout overriding. Currently, we do not accept more than one peg-out
         confirmation in the same update collections, but if in the future we do, then only one peg-out would be
         kept in the map, since the key used in the RSK tx hash that calls updateCollections would override the
         last one, thus resulting in losing funds. For this reason, we add this check that will alert anyone by
         throwing an exception during the development/QA phase when any code change introduces a bug allowing two
         entries to have the same key. So, informing on time the existence of this critical bug to pursue
         awareness and hint to rethink the changes being added.
         */
        checkIfEntryExistsInPegoutsWaitingForSignatures(txWaitingForSignatureKey, pegoutsWaitingForSignatures);

        pegoutsWaitingForSignatures.put(txWaitingForSignatureKey, confirmedPegout.getBtcTransaction());
        pegoutsWaitingForConfirmations.removeEntry(confirmedPegout);

        eventLogger.logPegoutConfirmed(confirmedPegout.getBtcTransaction().getHash(), confirmedPegout.getPegoutCreationRskBlockNumber());
    }

    private void checkIfEntryExistsInPegoutsWaitingForSignatures(Hash rskTxHash, Map<Hash, BtcTransaction> pegoutsWaitingForSignatures) {
        if (pegoutsWaitingForSignatures.containsKey(rskTxHash)) {
            String message = String.format(
                "An entry for the given rskTxHash %s already exists. Entry overriding is not allowed for pegoutsWaitingForSignatures map.",
                rskTxHash
            );
            logger.error("[checkIfEntryExistsInPegoutsWaitingForSignatures] {}", message);
            throw new IllegalStateException(message);
        }
    }

    /**
     * If federation change output value had to be increased to be non-dust, the federation now has
     * more BTC than it should. So, we burn some sBTC to make balances match.
     *
     * @param btcTx      The btc tx that was just completed
     * @param sentByUser The number of sBTC originaly sent by the user
     */
    private void adjustBalancesIfChangeOutputWasDust(BtcTransaction btcTx, Coin sentByUser, Wallet wallet) {
        if (btcTx.getOutputs().size() <= 1) {
            // If there is no change, do-nothing
            return;
        }
        Coin sumInputs = Coin.ZERO;
        for (TransactionInput transactionInput : btcTx.getInputs()) {
            sumInputs = sumInputs.add(transactionInput.getValue());
        }

        Coin change = btcTx.getValueSentToMe(wallet);
        Coin spentByFederation = sumInputs.subtract(change);
        if (spentByFederation.isLessThan(sentByUser)) {
            Coin coinsToBurn = sentByUser.subtract(spentByFederation);
            this.transferTo(BURN_ADDRESS, Weis.fromSatoshis(coinsToBurn));
        }
    }

    /**
     * Adds a federator signature to a btc release tx.
     * The hash for the signature must be calculated with Transaction.SigHash.ALL and anyoneCanPay=false. The signature must be canonical.
     * If enough signatures were added, ask federators to broadcast the btc release tx.
     *
     * @param federatorBtcPublicKey         Federator who is signing
     * @param signatures                    1 signature per btc tx input
     * @param releaseCreationRskTxHash      The hash of the release creation rsk tx
     */
    public void addSignature(BtcECKey federatorBtcPublicKey, List<byte[]> signatures, Hash releaseCreationRskTxHash) throws IOException {
        if (signatures == null || signatures.isEmpty()) {
            return;
        }

        Context.propagate(btcContext);

        if (isSvpOngoing() && isSvpSpendTx(releaseCreationRskTxHash)) {
            logger.info("[addSignature] Going to sign svp spend transaction with federator public key {}", federatorBtcPublicKey);
            addSvpSpendTxSignatures(federatorBtcPublicKey, signatures);
            return;
        }

        logger.info("[addSignature] Going to sign release transaction with federator public key {}", federatorBtcPublicKey);
        addReleaseSignatures(federatorBtcPublicKey, signatures, releaseCreationRskTxHash);
    }

    private void addReleaseSignatures(
        BtcECKey federatorPublicKey,
        List<byte[]> signatures,
        Hash releaseCreationRskTxHash
    ) throws IOException {

        BtcTransaction releaseTx = provider.getPegoutsWaitingForSignatures().get(releaseCreationRskTxHash);
        if (releaseTx == null) {
            logger.warn("[addReleaseSignatures] No tx waiting for signature for hash {}. Probably fully signed already.", releaseCreationRskTxHash);
            return;
        }
        if (!areSignaturesEnoughToSignAllTxInputs(releaseTx, signatures)) {
            return;
        }

        Optional<Federation> optionalFederation = getFederationFromPublicKey(federatorPublicKey);
        if (optionalFederation.isEmpty()) {
            logger.warn(
                "[addReleaseSignatures] Supplied federator btc public key {} does not belong to any of the federators.",
                federatorPublicKey
            );
            return;
        }

        Federation federation = optionalFederation.get();
        Optional<FederationMember> federationMember = federation.getMemberByBtcPublicKey(federatorPublicKey);
        if (federationMember.isEmpty()){
            logger.warn(
                "[addReleaseSignatures] Supplied federator btc public key {} doest not match any of the federator member btc public keys {}.",
                federatorPublicKey, federation.getBtcPublicKeys()
            );
            return;
        }
        FederationMember signingFederationMember = federationMember.get();

        byte[] releaseCreationRskTxHashSerialized = releaseCreationRskTxHash.getBytes().toArrayUnsafe();

        processSigning(signingFederationMember, signatures, releaseCreationRskTxHash, releaseTx);

        if (!BridgeUtils.hasEnoughSignatures(btcContext, releaseTx)) {
            logMissingSignatures(releaseTx, releaseCreationRskTxHash, federation);
            return;
        }

        logReleaseBtc(releaseTx, releaseCreationRskTxHashSerialized);
        provider.getPegoutsWaitingForSignatures().remove(releaseCreationRskTxHash);
    }

    private Optional<Federation> getFederationFromPublicKey(BtcECKey federatorPublicKey) {
        Federation retiringFederation = getRetiringFederation();
        Federation activeFederation = getActiveFederation();

        if (activeFederation.hasBtcPublicKey(federatorPublicKey)) {
            return Optional.of(activeFederation);
        }
        if (retiringFederation != null && retiringFederation.hasBtcPublicKey(federatorPublicKey)) {
            return Optional.of(retiringFederation);
        }

        return Optional.empty();
    }

    private boolean isSvpSpendTx(Hash releaseCreationRskTxHash) {
        return provider.getSvpSpendTxWaitingForSignatures()
            .map(Map.Entry::getKey)
            .filter(key -> key.equals(releaseCreationRskTxHash))
            .isPresent();
    }

    private void addSvpSpendTxSignatures(
        BtcECKey proposedFederatorPublicKey,
        List<byte[]> signatures
    ) {
        Federation proposedFederation = federationSupport.getProposedFederation()
            // This flow should never be reached. There should always be a proposed federation if svpIsOngoing.
            .orElseThrow(() -> new IllegalStateException("Proposed federation must exist when trying to sign the svp spend transaction."));
        Map.Entry<Hash, BtcTransaction> svpSpendTxWFS = provider.getSvpSpendTxWaitingForSignatures()
            // The svpSpendTxWFS should always be present at this point, since we already checked isTheSvpSpendTx.
            .orElseThrow(() -> new IllegalStateException("Svp spend tx waiting for signatures must exist"));
        FederationMember federationMember = proposedFederation.getMemberByBtcPublicKey(proposedFederatorPublicKey)
            .orElseThrow(() -> new IllegalStateException("Federator must belong to proposed federation to sign the svp spend transaction."));

        Hash svpSpendTxCreationRskTxHash = svpSpendTxWFS.getKey();
        BtcTransaction svpSpendTx = svpSpendTxWFS.getValue();

        if (!areSignaturesEnoughToSignAllTxInputs(svpSpendTx, signatures)) {
            return;
        }

        processSigning(federationMember, signatures, svpSpendTxCreationRskTxHash, svpSpendTx);
       
        // save current fed signature back in storage
        svpSpendTxWFS.setValue(svpSpendTx);
        provider.setSvpSpendTxWaitingForSignatures(svpSpendTxWFS);

        if (!BridgeUtils.hasEnoughSignatures(btcContext, svpSpendTx)) {
            logMissingSignatures(svpSpendTx, svpSpendTxCreationRskTxHash, proposedFederation);
            return;
        }

        logReleaseBtc(svpSpendTx, svpSpendTxCreationRskTxHash.getBytes().toArrayUnsafe());
        provider.clearSvpSpendTxWaitingForSignatures();
    }

    private boolean areSignaturesEnoughToSignAllTxInputs(BtcTransaction releaseTx, List<byte[]> signatures) {
        int inputsSize = releaseTx.getInputs().size();
        int signaturesSize = signatures.size();

        if (inputsSize != signaturesSize) {
            logger.warn("[areSignaturesEnoughToSignAllTxInputs] Expected {} signatures but received {}.", inputsSize, signaturesSize);
            return false;
        }
        return true;
    }

    private void logMissingSignatures(BtcTransaction btcTx, Hash releaseCreationRskTxHash, Federation federation) {
        int missingSignatures = BridgeUtils.countMissingSignatures(btcContext, btcTx);
        int neededSignatures = federation.getNumberOfSignaturesRequired();
        int signaturesCount = neededSignatures - missingSignatures;

        logger.debug("[logMissingSignatures] Tx {} not yet fully signed. Requires {}/{} signatures but has {}",
            releaseCreationRskTxHash, neededSignatures, federation.getSize(), signaturesCount);
    }

    private void logReleaseBtc(BtcTransaction btcTx, byte[] releaseCreationRskTxHashSerialized) {
        logger.info("[logReleaseBtc] Tx fully signed {}. Hex: {}", btcTx, Printable.hex(btcTx.bitcoinSerialize()));
        eventLogger.logReleaseBtc(btcTx, releaseCreationRskTxHashSerialized);
    }

    private void processSigning(
        FederationMember federatorMember,
        List<byte[]> signatures,
        Hash releaseCreationRskTxHash,
        BtcTransaction btcTx
    ) {
        // Build input sig hashes for signatures
        List<Sha256Hash> sigHashes = new ArrayList<>();
        for (int i = 0; i < btcTx.getInputs().size(); i++) {
            Sha256Hash sigHash = generateInputSigHash(btcTx, i);
            sigHashes.add(sigHash);
        }

        // Verify given signatures are correct before proceeding
        BtcECKey federatorBtcPublicKey = federatorMember.getBtcPublicKey();
        List<TransactionSignature> txSigs;
        try {
            txSigs = getTransactionSignatures(federatorBtcPublicKey, sigHashes, signatures);
        } catch (SignatureException e) {
            logger.error("[processSigning] Unable to proceed with signing as the transaction signatures are incorrect. {} ", e.getMessage());
            return;
        }

        // All signatures are correct. Proceed to signing
        boolean signed = sign(federatorBtcPublicKey, txSigs, sigHashes, releaseCreationRskTxHash, btcTx);

        if (signed) {
            eventLogger.logAddSignature(federatorMember, btcTx, releaseCreationRskTxHash.getBytes().toArrayUnsafe());
        }
    }

    private Sha256Hash generateInputSigHash(BtcTransaction btcTx, int inputIndex) {
        if (!inputHasWitness(btcTx, inputIndex)) {
            return generateSigHashForLegacyTransactionInput(btcTx, inputIndex);
        }

        return provider.getReleaseOutpointsValues(btcTx.getHash())
            .map(releaseOutpointsValues -> releaseOutpointsValues.get(inputIndex))
            .map(prevValue -> generateSigHashForSegwitTransactionInput(btcTx, inputIndex, prevValue))
            .orElseThrow(IllegalArgumentException::new);
    }

    private List<TransactionSignature> getTransactionSignatures(BtcECKey federatorBtcPublicKey, List<Sha256Hash> sigHashes, List<byte[]> signatures) throws SignatureException {
        List<BtcECKey.ECDSASignature> decodedSignatures = getDecodedSignatures(signatures);
        List<TransactionSignature> txSigs = new ArrayList<>();

        for (int i = 0; i < decodedSignatures.size(); i++) {
            BtcECKey.ECDSASignature decodedSignature = decodedSignatures.get(i);
            Sha256Hash sigHash = sigHashes.get(i);

            if (!federatorBtcPublicKey.verify(sigHash, decodedSignature)) {
                logger.warn(
                    "[getTransactionSignatures] Signature {} {} is not valid for hash {} and public key {}",
                    i,
                    Printable.hex(decodedSignature.encodeToDER()),
                    sigHash,
                    federatorBtcPublicKey
                );
                throw new SignatureException();
            }

            TransactionSignature txSig = new TransactionSignature(decodedSignature, BtcTransaction.SigHash.ALL, false);
            if (!txSig.isCanonical()) {
                logger.warn("[getTransactionSignatures] Signature {} {} is not canonical.", i, Printable.hex(decodedSignature.encodeToDER()));
                throw new SignatureException();
            }
            txSigs.add(txSig);
        }
        return txSigs;
    }

    private List<BtcECKey.ECDSASignature> getDecodedSignatures(List<byte[]> signatures) throws SignatureException {
        List<BtcECKey.ECDSASignature> decodedSignatures = new ArrayList<>();
        for (byte[] signature : signatures) {
            try {
                decodedSignatures.add(BtcECKey.ECDSASignature.decodeFromDER(signature));
            } catch (RuntimeException e) {
                int index = signatures.indexOf(signature);
                logger.warn("[getDecodedSignatures] Malformed signature for input {} : {}", index, Printable.hex(signature));
                throw new SignatureException();
            }
        }
        return decodedSignatures;
    }

    private boolean sign(
        BtcECKey federatorBtcPublicKey,
        List<TransactionSignature> txSigs,
        List<Sha256Hash> sigHashes,
        Hash releaseCreationRskTxHash,
        BtcTransaction btcTx
    ) {
        boolean signed = false;
        for (int i = 0; i < sigHashes.size(); i++) {
            Sha256Hash sigHash = sigHashes.get(i);

            boolean alreadySignedByThisFederator =
                BridgeUtils.isInputSignedByThisFederator(btcTx, i, federatorBtcPublicKey, sigHash);

            if (alreadySignedByThisFederator) {
                logger.warn("[sign] Input {} of tx {} already signed by this federator.", i, releaseCreationRskTxHash);
                break;
            }

            Optional<Script> redeemScript = extractRedeemScriptFromInput(btcTx, i);
            if (redeemScript.isEmpty()) {
                logger.debug("[sign] Couldn't extract redeem script from input {} of tx {}.", i, btcTx.getHash());
                break;
            }

            try {
                int sigIndex = getSigInsertionIndex(btcTx, i, sigHash, federatorBtcPublicKey);
                Script outputScript = buildOutputScript(btcTx, i, redeemScript.get());
                signInput(btcTx, i, txSigs.get(i), sigIndex, outputScript);

                logger.debug("[sign] Tx input {} for tx {} signed.", i, releaseCreationRskTxHash);
                signed = true;
            } catch (IllegalStateException e) {
                Federation retiringFederation = getRetiringFederation();
                if (getActiveFederation().hasBtcPublicKey(federatorBtcPublicKey)) {
                    logger.debug("[sign] A member of the active federation is trying to sign a tx of the retiring one");
                } else if (retiringFederation != null && retiringFederation.hasBtcPublicKey(federatorBtcPublicKey)) {
                    logger.debug("[sign] A member of the retiring federation is trying to sign a tx of the active one");
                }
                return false;
            }
        }

        return signed;
    }

    private Script buildOutputScript(BtcTransaction btcTx, int inputIndex, Script redeemScript) {
        if (!inputHasWitness(btcTx, inputIndex)) {
            return ScriptBuilder.createP2SHOutputScript(redeemScript);
        }
        return ScriptBuilder.createP2SHP2WSHOutputScript(redeemScript);
    }

    /**
     * Returns the btc tx that federators need to sign or broadcast
     * @return a StateForFederator serialized in RLP
     */
    public byte[] getStateForBtcReleaseClient() throws IOException {
        StateForFederator stateForFederator = new StateForFederator(provider.getPegoutsWaitingForSignatures());
        return stateForFederator.encodeToRlp();
    }

   /**
     * Retrieves the current SVP spend transaction state for the SVP client.
     *
     * <p>
     * This method checks if there is an SVP spend transaction waiting for signatures, and if so, it serializes 
     * the state into RLP format. If no transaction is waiting, it returns an encoded empty RLP list.
     * </p>
     *
     * @return A byte array representing the RLP-encoded state of the SVP spend transaction. If no transaction 
     *         is waiting, returns a double RLP-encoded empty list.
     */
    public byte[] getStateForSvpClient() {
        return provider.getSvpSpendTxWaitingForSignatures()
            .map(StateForProposedFederator::new)
            .map(StateForProposedFederator::encodeToRlp)
            .orElse(RskRlp.encode(out -> {
                out.startList();
                out.writeEmptyList();
                out.endList();
            }));
    }

    /**
     * Returns the insternal state of the bridge
     * @return a BridgeState serialized in RLP
     */
    public byte[] getStateForDebugging() throws IOException, BlockStoreException {
        int btcBlockchainBestChainHeight = getBtcBlockchainBestChainHeight();
        long nextPegoutCreationBlockNumber = provider.getNextPegoutHeight().orElse(0L);
        List<UTXO> newFederationBtcUTXOs = federationSupport.getNewFederationBtcUTXOs();
        SortedMap<Hash, BtcTransaction> pegoutsWaitingForSignatures = provider.getPegoutsWaitingForSignatures();
        ReleaseRequestQueue releaseRequestQueue = provider.getReleaseRequestQueue();
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        BridgeState stateForDebugging = new BridgeState(
            btcBlockchainBestChainHeight,
            nextPegoutCreationBlockNumber,
            newFederationBtcUTXOs,
            pegoutsWaitingForSignatures,
            releaseRequestQueue,
            pegoutsWaitingForConfirmations
        );

        return stateForDebugging.getEncoded();
    }

    /**
     * Returns the bitcoin blockchain best chain height know by the bridge contract
     */
    public int getBtcBlockchainBestChainHeight() throws IOException, BlockStoreException {
        return getBtcBlockchainChainHead().getHeight();
    }

    /**
     * Returns the bitcoin blockchain initial stored block height
     */
    public int getBtcBlockchainInitialBlockHeight() throws IOException {
        return getLowestBlock().getHeight();
    }

    public byte[] getBtcBlockchainBestBlockHeader() throws BlockStoreException, IOException {
        return serializeBlockHeader(getBtcBlockchainChainHead());
    }

    public byte[] getBtcBlockchainBlockHeaderByHash(Sha256Hash hash) throws IOException, BlockStoreException {
        this.ensureBtcBlockStore();

        return serializeBlockHeader(btcBlockStore.get(hash));
    }

    public byte[] getBtcBlockchainBlockHeaderByHeight(int height) throws BlockStoreException, IOException {
        Context.propagate(btcContext);
        this.ensureBtcBlockStore();

        StoredBlock block = btcBlockStore.getStoredBlockAtMainChainHeight(height);

        return serializeBlockHeader(block);
    }

    public byte[] getBtcBlockchainParentBlockHeaderByHash(Sha256Hash hash) throws IOException, BlockStoreException {
        this.ensureBtcBlockStore();

        StoredBlock block = btcBlockStore.get(hash);

        if (block == null) {
            return new byte[0];
        }

        return serializeBlockHeader(btcBlockStore.get(block.getHeader().getPrevBlockHash()));
    }

    public Sha256Hash getBtcBlockchainBlockHashAtDepth(int depth) throws BlockStoreException, IOException {
        Context.propagate(btcContext);
        this.ensureBtcBlockStore();

        StoredBlock head = btcBlockStore.getChainHead();
        int maxDepth = head.getHeight() - getLowestBlock().getHeight();

        if (depth < 0 || depth > maxDepth) {
            throw new IndexOutOfBoundsException(String.format("Depth must be between 0 and %d", maxDepth));
        }

        StoredBlock blockAtDepth = btcBlockStore.getStoredBlockAtMainChainDepth(depth);
        return blockAtDepth.getHeader().getHash();
    }

    public Long getBtcTransactionConfirmationsGetCost(Object[] args) {
        final long BASIC_COST = 27_000;
        final long STEP_COST = 315;
        final long DOUBLE_HASH_COST = 144; // 72 * 2. 72 is the cost of the hash operation

        Sha256Hash btcBlockHash;
        int branchHashesSize;
        try {
            btcBlockHash = Sha256Hash.wrap((byte[]) args[1]);
            Object[] merkleBranchHashesArray = (Object[]) args[3];
            branchHashesSize = merkleBranchHashesArray.length;
        } catch (NullPointerException | IllegalArgumentException e) {
            return BASIC_COST;
        }

        // Dynamic cost based on the depth of the block that contains
        // the transaction. Find such depth first, then calculate
        // the cost.
        Context.propagate(btcContext);
        try {
            this.ensureBtcBlockStore();
            final StoredBlock block = getBlockKeepingTestnetConsensus(btcBlockHash);

            // Block not found, default to basic cost
            if (block == null) {
                return BASIC_COST;
            }

            final int bestChainHeight = getBtcBlockchainBestChainHeight();

            // Make sure calculated depth is >= 0
            final int blockDepth = Math.max(0, bestChainHeight - block.getHeight());

            // Block too deep, default to basic cost
            if (blockDepth > BTC_TRANSACTION_CONFIRMATION_MAX_DEPTH) {
                return BASIC_COST;
            }

            return BASIC_COST + blockDepth*STEP_COST + branchHashesSize*DOUBLE_HASH_COST;
        } catch (IOException | BlockStoreException e) {
            logger.warn("getBtcTransactionConfirmationsGetCost btcBlockHash:{} there was a problem " +
                    "gathering the block depth while calculating the gas cost. " +
                    "Defaulting to basic cost.", btcBlockHash, e);
            return BASIC_COST;
        }
    }

    /**
     * @param btcTxHash The BTC transaction Hash
     * @param btcBlockHash The BTC block hash
     * @param merkleBranch The merkle branch
     * @throws BlockStoreException
     * @throws IOException
     */
    public Integer getBtcTransactionConfirmations(Sha256Hash btcTxHash, Sha256Hash btcBlockHash, MerkleBranch merkleBranch) throws BlockStoreException, IOException {
        Context.propagate(btcContext);
        this.ensureBtcBlockChain();

        // Get the block using the given block hash
        StoredBlock block = getBlockKeepingTestnetConsensus(btcBlockHash);
        if (block == null) {
            return BTC_TRANSACTION_CONFIRMATION_INEXISTENT_BLOCK_HASH_ERROR_CODE;
        }

        final int bestChainHeight = getBtcBlockchainBestChainHeight();

        // Prevent diving too deep in the blockchain to avoid high processing costs
        final int blockDepth = Math.max(0, bestChainHeight - block.getHeight());
        if (blockDepth > BTC_TRANSACTION_CONFIRMATION_MAX_DEPTH) {
            return BTC_TRANSACTION_CONFIRMATION_BLOCK_TOO_OLD_ERROR_CODE;
        }

        try {
            StoredBlock storedBlock = btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight());
            // Make sure it belongs to the best chain
            if (storedBlock == null || !storedBlock.equals(block)){
                return BTC_TRANSACTION_CONFIRMATION_BLOCK_NOT_IN_BEST_CHAIN_ERROR_CODE;
            }
        } catch (BlockStoreException e) {
            logger.warn("Illegal state trying to get block with hash {}", btcBlockHash, e);
            return BTC_TRANSACTION_CONFIRMATION_INCONSISTENT_BLOCK_ERROR_CODE;
        }

        Sha256Hash merkleRoot = merkleBranch.reduceFrom(btcTxHash);

        if (!isBlockMerkleRootValid(merkleRoot, block.getHeader())) {
            return BTC_TRANSACTION_CONFIRMATION_INVALID_MERKLE_BRANCH_ERROR_CODE;
        }

        return bestChainHeight - block.getHeight() + 1;
    }

    private StoredBlock getBlockKeepingTestnetConsensus(Sha256Hash btcBlockHash) throws BlockStoreException {
        long rskBlockNumber = 5_148_285;

        boolean networkIsTestnet = bridgeConstants.getBtcParams().equals(NetworkParameters.fromID(NetworkParameters.ID_TESTNET));
        Sha256Hash blockHash = Sha256Hash.wrap("00000000e8e7b540df01a7067e020fd7e2026bf86289def2283a35120c1af379");

        // DO NOT MODIFY.
        // This check is needed since this block caused a misbehaviour
        // for being stored in the cache but not in the storage
        if (host.blockNumber() == rskBlockNumber
            && networkIsTestnet
            && btcBlockHash.equals(blockHash)
        ) {
            byte[] rawBtcBlockHeader = Bytes.fromHexString("000000203b5d178405c4e6e7dc07d63d6de5db1342044791721654760c00000000000000796cf6743a036300b43fb3abe6703d04a7999751b6d5744f20327d1175320bd37b954e66ffff001d56dc11ce").toArrayUnsafe();

            BtcBlock btcBlockHeader = new BtcBlock(bridgeConstants.getBtcParams(), rawBtcBlockHeader);
            BigInteger btcBlockChainWork = new BigInteger("000000000000000000000000000000000000000000000ddeb5fbcd969312a77c", 16);
            int btcBlockNumber = 2_817_125;

            return new StoredBlock(btcBlockHeader, btcBlockChainWork, btcBlockNumber);
        }

        return btcBlockStore.get(btcBlockHash);
    }

    /**
     * Returns whether a given btc transaction hash has already
     * been processed by the bridge.
     * @param btcTxHash the btc tx hash to check.
     * @return a Boolean indicating whether the given btc tx hash was
     * already processed by the bridge.
     * @throws IOException
     */
    public Boolean isBtcTxHashAlreadyProcessed(Sha256Hash btcTxHash) throws IOException {
        return provider.getHeightIfBtcTxhashIsAlreadyProcessed(btcTxHash).isPresent();
    }

    /**
     * Returns the RSK blockchain height a given btc transaction hash
     * was processed at by the bridge.
     * @param btcTxHash the btc tx hash for which to retrieve the height.
     * @return a Long with the processed height. If the hash was not processed
     * -1 is returned.
     * @throws IOException
     */
    public Long getBtcTxHashProcessedHeight(Sha256Hash btcTxHash) throws IOException {
        // Return -1 if the transaction hasn't been processed
        return provider.getHeightIfBtcTxhashIsAlreadyProcessed(btcTxHash).orElse(-1L);
    }

    /**
     * Returns if tx was already processed by the bridge
     * @param btcTxHash the btc tx hash for which to retrieve the height.
     * @return true or false according
     * @throws  IOException
     * */
    protected boolean isAlreadyBtcTxHashProcessed(Sha256Hash btcTxHash) throws IOException {
        if (getBtcTxHashProcessedHeight(btcTxHash) > -1L) {
            logger.warn(
                "[isAlreadyBtcTxHashProcessed] Supplied Btc Tx {} was already processed",
                btcTxHash
            );
            return true;
        }

        return false;
    }

    /**
     * Returns the currently active federation.
     * See getActiveFederationReference() for details.
     * @return the currently active federation.
     */
    public Federation getActiveFederation() {
        return federationSupport.getActiveFederation();
    }

    @Nullable
    public Federation getRetiringFederation() {
        return federationSupport.getRetiringFederation();
    }

    public Optional<Federation> getProposedFederation() {
        return federationSupport.getProposedFederation();
    }

    public Address getActiveFederationAddress() {
        return federationSupport.getActiveFederationAddress();
    }

    public Integer getActiveFederationSize() {
        return federationSupport.getActiveFederationSize();
    }

    public Integer getActiveFederationThreshold() {
        return federationSupport.getActiveFederationThreshold();
    }

    public byte[] getActiveFederatorPublicKeyOfType(int index, FederationMember.KeyType keyType) {
        return federationSupport.getActiveFederatorPublicKeyOfType(index, keyType);
    }

    public Instant getActiveFederationCreationTime() {
        return federationSupport.getActiveFederationCreationTime();
    }

    public long getActiveFederationCreationBlockNumber() {
        return federationSupport.getActiveFederationCreationBlockNumber();
    }

    public Address getRetiringFederationAddress() {
        return federationSupport.getRetiringFederationAddress();
    }

    public Integer getRetiringFederationSize() {
        return federationSupport.getRetiringFederationSize();
    }

    public Integer getRetiringFederationThreshold() {
        return federationSupport.getRetiringFederationThreshold();
    }

    public byte[] getRetiringFederatorPublicKeyOfType(int index, FederationMember.KeyType keyType) {
        return federationSupport.getRetiringFederatorPublicKeyOfType(index, keyType);
    }

    public Instant getRetiringFederationCreationTime() {
        return federationSupport.getRetiringFederationCreationTime();
    }

    public long getRetiringFederationCreationBlockNumber() {
        return federationSupport.getRetiringFederationCreationBlockNumber();
    }

    public Integer voteFederationChange(CallContext tx, ABICallSpec callSpec) {
        return federationSupport.voteFederationChange(tx, callSpec, eventLogger);
    }

    public Hash getPendingFederationHash() {
        return federationSupport.getPendingFederationHash();
    }

    public Integer getPendingFederationSize() {
        return federationSupport.getPendingFederationSize();
    }

    public byte[] getPendingFederatorPublicKeyOfType(int index, FederationMember.KeyType keyType) {
        return federationSupport.getPendingFederatorPublicKeyOfType(index, keyType);
    }

    public Optional<Address> getProposedFederationAddress() {
        return federationSupport.getProposedFederationAddress();
    }

    public Optional<Integer> getProposedFederationSize() {
        return federationSupport.getProposedFederationSize();
    }

    public Optional<Instant> getProposedFederationCreationTime() {
        return federationSupport.getProposedFederationCreationTime();
    }

    public Optional<Long> getProposedFederationCreationBlockNumber() {
        return federationSupport.getProposedFederationCreationBlockNumber();
    }

    public Optional<byte[]> getProposedFederatorPublicKeyOfType(int index, FederationMember.KeyType keyType) {
        return federationSupport.getProposedFederatorPublicKeyOfType(index, keyType);
    }

    public Coin getFeePerKb() {
        return feePerKbSupport.getFeePerKb();
    }

    public Integer voteFeePerKbChange(CallContext tx, Coin feePerKb) {
        return feePerKbSupport.voteFeePerKbChange(tx, feePerKb);
    }

    public Coin getLockingCap() {
        return lockingCapSupport.getLockingCap().orElse(null);
    }

    public Optional<Script> getActiveFederationRedeemScript() {
        return federationSupport.getActiveFederationRedeemScript();
    }

    public boolean increaseLockingCap(CallContext tx, Coin newLockingCap) throws LockingCapIllegalArgumentException {
        return lockingCapSupport.increaseLockingCap(tx, newLockingCap);
    }

    public void registerBtcCoinbaseTransaction(
        byte[] btcTxSerialized,
        Sha256Hash blockHash,
        byte[] pmtSerialized,
        Sha256Hash witnessMerkleRoot,
        byte[] witnessReservedValue
    ) throws VMException {
        final String LOG_PREFIX = "[registerBtcCoinbaseTransaction]";
        Context.propagate(btcContext);
        try{
            this.ensureBtcBlockStore();
        }catch (BlockStoreException | IOException e) {
            String message = String.format("Exception in registerBtcCoinbaseTransaction. %s", e.getMessage());
            logger.warn("{} {}", LOG_PREFIX, message);
            throw new VMException(message, e);
        }

        Sha256Hash btcTxHash = BtcTransactionFormatUtils.calculateBtcTxHash(btcTxSerialized);
        logger.debug("{} Going to register coinbase information for btcTx: {}", LOG_PREFIX, btcTxHash);

        if (witnessReservedValue.length != 32) {
            String message = String.format(
                "Witness reserved value length can't be different than 32 bytes. Value received: %s",
                Printable.hex(witnessReservedValue)
            );
            logger.warn("{} {}", LOG_PREFIX, message);
            throw new BridgeIllegalArgumentException(message);
        }
        logger.trace("{} Witness reserved value: {}", LOG_PREFIX, Printable.hex(witnessReservedValue));

        if (!PartialMerkleTreeFormatUtils.hasExpectedSize(pmtSerialized)) {
            String message = String.format(
                "PartialMerkleTree doesn't have expected size. Value received: %s",
                Printable.hex(pmtSerialized)
            );
            logger.warn("{} {}", LOG_PREFIX, message);
            throw new BridgeIllegalArgumentException(message);
        }

        Sha256Hash merkleRoot;
        try {
            PartialMerkleTree pmt = new PartialMerkleTree(networkParameters, pmtSerialized, 0);
            List<Sha256Hash> hashesInPmt = new ArrayList<>();
            merkleRoot = pmt.getTxnHashAndMerkleRoot(hashesInPmt);
            if (!hashesInPmt.contains(btcTxHash)) {
                logger.warn(
                    "{} Supplied btc tx {} is not in the supplied partial merkle tree {}",
                    LOG_PREFIX,
                    btcTxHash,
                    pmt
                );
                return;
            }
        } catch (VerificationException e) {
            String message = String.format("Partial merkle tree could not be parsed. %s", Printable.hex(pmtSerialized));
            logger.warn("{} {}", LOG_PREFIX, message);
            throw new BridgeIllegalArgumentException(message, e);
        }
        logger.trace("{} Merkle root: {}", LOG_PREFIX, merkleRoot);

        // Check merkle root equals btc block merkle root at the specified height in the btc best chain
        // Btc blockstore is available since we've already queried the best chain height
        StoredBlock storedBlock = null;
        try {
            storedBlock = btcBlockStore.get(blockHash);
        } catch (BlockStoreException e) {
            logger.error(
                "{} Error gettin block {} from block store. {}",
                LOG_PREFIX,
                blockHash,
                e.getMessage()
            );
        }

        if (storedBlock == null) {
            String message = String.format("Block %s not yet registered", blockHash);
            logger.warn("{} {}", LOG_PREFIX, message);
            throw new BridgeIllegalArgumentException(message);
        }
        logger.trace(
            "{} Found block with hash {} at height {}",
            LOG_PREFIX,
            blockHash,
            storedBlock.getHeight()
        );

        BtcBlock blockHeader = storedBlock.getHeader();
        if (!blockHeader.getMerkleRoot().equals(merkleRoot)) {
            String panicMessage = String.format(
                "Btc Tx %s Supplied merkle root %s does not match block's merkle root %s",
                btcTxHash,
                merkleRoot,
                blockHeader.getMerkleRoot()
            );
            logger.warn("{} {}", LOG_PREFIX, panicMessage);
            return;
        }

        BtcTransaction btcTx = new BtcTransaction(networkParameters, btcTxSerialized);
        btcTx.verify();

        validateWitnessInformation(btcTx, witnessMerkleRoot, witnessReservedValue);

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(witnessMerkleRoot);
        provider.setCoinbaseInformation(blockHeader.getHash(), coinbaseInformation);

        logger.debug("{} Registered coinbase information for btc tx {}", LOG_PREFIX, btcTxHash);
    }

    private void validateWitnessInformation(
        BtcTransaction coinbaseTransaction,
        Sha256Hash witnessMerkleRoot,
        byte[] witnessReservedValue
    ) throws BridgeIllegalArgumentException {
        Optional<Sha256Hash> expectedWitnessCommitment = findWitnessCommitment(coinbaseTransaction);
        Sha256Hash calculatedWitnessCommitment = Sha256Hash.twiceOf(witnessMerkleRoot.getReversedBytes(), witnessReservedValue);

        if (expectedWitnessCommitment.isEmpty() || !expectedWitnessCommitment.get().equals(calculatedWitnessCommitment)) {
            String message = String.format(
                "[btcTx: %s] Witness commitment does not match. Expected: %s, Calculated: %s",
                coinbaseTransaction.getHash(),
                expectedWitnessCommitment.orElse(null),
                calculatedWitnessCommitment
            );
            logger.warn("[validateWitnessInformation] {}", message);
            throw new BridgeIllegalArgumentException(message);
        }
        logger.debug("[validateWitnessInformation] Witness commitment {} validated for btc tx {}", calculatedWitnessCommitment, coinbaseTransaction.getHash());
    }

    public boolean hasBtcBlockCoinbaseTransactionInformation(Sha256Hash blockHash) {
        CoinbaseInformation coinbaseInformation = provider.getCoinbaseInformation(blockHash);
        return coinbaseInformation != null;
    }

    public long getActiveFederationCreationBlockHeight() {
        return federationSupport.getActiveFederationCreationBlockHeight();
    }

    public long getNextPegoutCreationBlockNumber() {
        return provider.getNextPegoutHeight().orElse(0L);
    }

    public int getQueuedPegoutsCount() throws IOException {
        return provider.getReleaseRequestQueueSize();
    }

    public Coin getEstimatedFeesForNextPegOutEvent() throws IOException {
        //  This method returns the fees of a peg-out transaction containing (N+2) outputs and 2 inputs,
        //  where N is the number of peg-outs requests waiting in the queue.

        return getEstimatedFeesFromPegoutTransactionSimulation();
    }

    private Coin getEstimatedFeesFromInputsAndOutputsCount() throws IOException {

        int outputsCount = getQueuedPegoutsCount() + 2;
        int inputsCount = 2;

        int pegoutTxSize = BridgeUtils.calculatePegoutTxSize(getActiveFederation(), inputsCount, outputsCount);

        Coin feePerKB = getFeePerKb();

        return feePerKB
            .multiply(pegoutTxSize) // times the size in bytes
            .divide(1000);
    }

    private Coin getEstimatedFeesFromPegoutTransactionSimulation() throws IOException {
        ReleaseRequestQueue releaseRequestQueue = provider.getReleaseRequestQueue();
        List<ReleaseRequestQueue.Entry> releaseRequestListCopy = new ArrayList<>(
            releaseRequestQueue.getEntries().stream()
                .map(rr -> new ReleaseRequestQueue.Entry(rr.getDestination(), rr.getAmount())).toList());

        // One more pegout to estimate what the fee would be for with an extra pegout if requested
        releaseRequestListCopy.add(new ReleaseRequestQueue.Entry(new BtcECKey().toAddress(this.networkParameters), Coin.valueOf(1, 0)));

        Wallet activeFederationWallet = getActiveFederationWallet();
        Federation activeFederation = getActiveFederation();

        ReleaseTransactionBuilder txBuilder = new ReleaseTransactionBuilder(
            btcContext.getParams(),
            activeFederationWallet,
            activeFederation.getFormatVersion(),
            activeFederation.getAddress(),
            getFeePerKb()
        );

        ReleaseTransactionBuilder.BuildResult buildResult = txBuilder.buildBatchedPegouts(releaseRequestListCopy);

        if(buildResult.getResponseCode() != ReleaseTransactionBuilder.Response.SUCCESS) {
            logger.debug(
                "[getEstimatedFeesFromPegoutTransactionSimulation] Simulated pegout btc transaction failed to be created with response code: {}. Cannot simulate a pegout btc release transaction. Will fallback to old logic."
            , buildResult.getResponseCode());
            return getEstimatedFeesFromInputsAndOutputsCount();
        }

        Coin inputSum = buildResult.getBtcTx().getInputSum();
        Coin outputSum = buildResult.getBtcTx().getOutputSum();

        return inputSum.minus(outputSum);
    }


    private StoredBlock getBtcBlockchainChainHead() throws IOException, BlockStoreException {
        // Gather the current btc chain's head
        // IMPORTANT: we assume that getting the chain head from the btc blockstore
        // is enough since we're not manipulating the blockchain here, just querying it.
        this.ensureBtcBlockStore();
        return btcBlockStore.getChainHead();
    }

    /**
     * Returns the first bitcoin block we have. It is either a checkpoint or the genesis
     */
    private StoredBlock getLowestBlock() throws IOException {
        InputStream checkpoints = this.getCheckPoints();
        if (checkpoints == null) {
            BtcBlock genesis = networkParameters.getGenesisBlock();
            return new StoredBlock(genesis, genesis.getWork(), 0);
        }
        CheckpointManager manager = new CheckpointManager(networkParameters, checkpoints);
        long time = getActiveFederation().getCreationTime().toEpochMilli();
        // Go back 1 week to match CheckpointManager.checkpoint() behaviour
        time -= 86400 * 7;
        return manager.getCheckpointBefore(time);
    }

    private Pair<BtcTransaction, List<UTXO>> createMigrationTransaction(Wallet originWallet, Address destinationAddress) {
        Coin expectedMigrationValue = originWallet.getBalance();
        logger.debug("[createMigrationTransaction] Balance to migrate: {}", expectedMigrationValue);
        for(;;) {
            ReleaseTransactionBuilder txBuilder = new ReleaseTransactionBuilder(
                networkParameters,
                originWallet,
                getRetiringFederation().getFormatVersion(),
                destinationAddress,
                getFeePerKb()
            );
            ReleaseTransactionBuilder.BuildResult result = txBuilder.buildMigrationTransaction(expectedMigrationValue, destinationAddress);

            switch (result.getResponseCode()) {
                case SUCCESS -> {
                    BtcTransaction migrationBtcTx = result.getBtcTx();
                    for (TransactionInput transactionInput : migrationBtcTx.getInputs()) {
                        transactionInput.disconnect();
                    }
                    return Pair.of(migrationBtcTx, result.getSelectedUTXOs());
                }

                case UTXO_PROVIDER_EXCEPTION ->
                    throw new RuntimeException("[createMigrationTransaction] Unexpected UTXO provider error");

                case DUSTY_SEND_REQUESTED ->
                    throw new IllegalStateException("[createMigrationTransaction] Retiring federation wallet cannot be emptied");

                case INSUFFICIENT_MONEY, EXCEED_MAX_TRANSACTION_SIZE, COULD_NOT_ADJUST_DOWNWARDS ->
                    expectedMigrationValue = expectedMigrationValue.divide(2);
            }
        }
    }

    // Make sure the local bitcoin blockchain is instantiated
    private void ensureBtcBlockChain() throws IOException, BlockStoreException {
        this.ensureBtcBlockStore();

        if (this.btcBlockChain == null) {
            this.btcBlockChain = new BtcBlockChain(btcContext, btcBlockStore);
        }
    }

    // Make sure the local bitcoin blockstore is instantiated
    private void ensureBtcBlockStore() throws IOException, BlockStoreException {
        if(btcBlockStore == null) {
            btcBlockStore = btcBlockStoreFactory.newInstance(
                host,
                bridgeConstants,
                provider
            );
            if (this.btcBlockStore.getChainHead().getHeader().getHash().equals(networkParameters.getGenesisBlock().getHash())) {
                // We are building the blockstore for the first time, so we have not set the checkpoints yet.
                long time = federationSupport.getActiveFederation().getCreationTime().toEpochMilli();
                InputStream checkpoints = this.getCheckPoints();
                if (time > 0 && checkpoints != null) {
                    CheckpointManager.checkpoint(networkParameters, checkpoints, this.btcBlockStore, time);
                }
            }
        }
    }

    private void generateRejectionReleaseWithWalletProvider(
        BtcTransaction btcTx,
        Address btcRefundAddress,
        Hash rskTxHash,
        Coin totalAmount,
        WalletProvider walletProvider
    ) throws IOException {
        // non-flyover wallet provider implementation does not use the addresses
        List<Address> emptyList = new ArrayList<>();
        Wallet wallet = walletProvider.provide(btcTx, emptyList);

        Federation federation = getFederationFromTxOutputs(btcTx);
        generateRejectionReleaseFromFederation(btcTx, btcRefundAddress, federation, rskTxHash, totalAmount, wallet);
    }

    // Having to create a rejection release with outputs for both
    // p2sh-erp retiring fed and p2sh-p2wsh-erp active fed would be problematic
    // since the redeem input data goes in different places
    // (script sig when legacy, witness when segwit).
    // The decision for this scenario is to choose the active federation format version.
    //
    // Disclaimer: a transaction like this will never be correctly signed,
    // because the addSignature method signs all the tx inputs with the received key,
    // so we don't really care about this very rare case.
    // But it's worth to explain the decision and the expected behaviour.
    private Federation getFederationFromTxOutputs(BtcTransaction btcTx) {
        // checking against active fed first
        Federation activeFederation = getActiveFederation();
        if (outputsMatchFederation(btcTx, activeFederation)){
            return activeFederation;
        }

        // and then against retiring fed
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation != null && outputsMatchFederation(btcTx, retiringFederation)) {
            return retiringFederation;
        }

        throw new IllegalStateException("Couldn't extract federation from btcTx outputs.");
    }

    private boolean outputsMatchFederation(BtcTransaction btcTx, Federation federation) {
        for (TransactionOutput output : btcTx.getOutputs()) {
            Address extractedAddress = output.getAddressFromP2SH(networkParameters);

            if (extractedAddress != null && extractedAddress.equals(federation.getAddress())) {
                return true;
            }
        }

        return false;
    }


    private void generateRejectionReleaseFromFederation(
        BtcTransaction btcTx,
        Address btcRefundAddress,
        Federation federation,
        Hash rskTxHash,
        Coin totalAmount,
        Wallet wallet
    ) throws IOException {

        ReleaseTransactionBuilder txBuilder = new ReleaseTransactionBuilder(
            btcContext.getParams(),
            wallet,
            federation.getFormatVersion(),
            btcRefundAddress,
            getFeePerKb()
        );

        ReleaseTransactionBuilder.BuildResult buildReturnResult = txBuilder.buildEmptyWalletTo(btcRefundAddress);
        if (buildReturnResult.getResponseCode() != ReleaseTransactionBuilder.Response.SUCCESS) {
            logger.warn(
                "[generateRejectionReleaseFromFederation] Rejecting peg-in tx could not be built due to {}: Btc peg-in txHash {}. Refund to address: {}. RskTxHash: {}. Value: {}",
                buildReturnResult.getResponseCode(),
                btcTx.getHash(),
                btcRefundAddress,
                rskTxHash,
                totalAmount
            );
            logger.error("[panic] {} {}", "peg-in-refund", String.format("peg-in money return tx build for btc tx %s error. Return was to %s. Tx %s. Value %s. Reason %s", btcTx.getHash(), btcRefundAddress, rskTxHash, totalAmount, buildReturnResult.getResponseCode()));
            return;
        }

        logger.info(
            "[generateRejectionReleaseFromFederation] Rejecting peg-in tx built successfully: Refund to address: {}. RskTxHash: {}. Value {}.",
            btcRefundAddress,
            rskTxHash,
            totalAmount
        );

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();
        BtcTransaction refundPegoutTransaction = buildReturnResult.getBtcTx();
        settleReleaseRejection(pegoutsWaitingForConfirmations, refundPegoutTransaction, rskTxHash, totalAmount);
    }

    private void settleReleaseRejection(PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations, BtcTransaction releaseRejectedTransaction, Hash releaseCreationTxHash, Coin requestedAmount) {
        addPegoutToPegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations, releaseRejectedTransaction, releaseCreationTxHash);
        logReleaseRequested(releaseCreationTxHash, releaseRejectedTransaction, requestedAmount);
        processReleaseTransactionInfo(releaseRejectedTransaction);
    }

    private void generateRejectionRelease(
        BtcTransaction btcTx,
        Address senderBtcAddress,
        Hash rskTxHash,
        Coin totalAmount
    ) throws IOException {
        WalletProvider walletProvider = (BtcTransaction btcTransaction, List<Address> addresses) -> {
            // Build the list of UTXOs in the BTC transaction sent to either the active
            // or retiring federation
            List<UTXO> utxosToUse = btcTx.getWalletOutputs(
                getNoSpendWalletForLiveFederations()
            )
                .stream()
                .map(output ->
                    new UTXO(
                        btcTx.getHash(),
                        output.getIndex(),
                        output.getValue(),
                        0,
                        btcTx.isCoinBase(),
                        output.getScriptPubKey()
                    )
                )
                .toList();
            // Use the list of UTXOs to build a transaction builder
            // for the return btc transaction generation
            return getUTXOBasedWalletForLiveFederations(utxosToUse);
        };

        generateRejectionReleaseWithWalletProvider(btcTx, senderBtcAddress, rskTxHash, totalAmount, walletProvider);
    }

    private boolean verifyLockDoesNotSurpassLockingCap(BtcTransaction btcTx, Coin totalAmount) {
        Optional<Coin> lockingCap = lockingCapSupport.getLockingCap();
        if (lockingCap.isEmpty()) {
            return true;
        }

        Coin fedCurrentFunds = getBtcLockedInFederation();
        logger.trace("Evaluating locking cap for: TxId {}. Value to lock {}. Current funds {}. Current locking cap {}", btcTx.getHash(true), totalAmount, fedCurrentFunds, lockingCap);
        Coin fedUTXOsAfterThisLock = fedCurrentFunds.add(totalAmount);
        // If the federation funds (including this new UTXO) are smaller than or equals to the current locking cap, we are fine.
        if (fedUTXOsAfterThisLock.compareTo(lockingCap.get()) <= 0) {
            return true;
        }

        logger.info("locking cap exceeded! btc Tx {}", btcTx);
        return false;
    }

    private Coin getBtcLockedInFederation() {
        Coin maxRbtc = this.bridgeConstants.getMaxRbtc();
        Coin currentBridgeBalance = Weis.toSatoshis(host.balanceOf(BridgeAddresses.BRIDGE));

        return maxRbtc.subtract(currentBridgeBalance);
    }

    @VisibleForTesting
    protected boolean isBlockMerkleRootValid(Sha256Hash merkleRoot, BtcBlock blockHeader) {
        boolean isValid = false;

        if (blockHeader.getMerkleRoot().equals(merkleRoot)) {
            logger.trace("block merkle root is valid");
            isValid = true;
        }
        else {
            CoinbaseInformation coinbaseInformation = provider.getCoinbaseInformation(blockHeader.getHash());
            if (coinbaseInformation == null) {
                logger.trace("coinbase information for block {} is not yet registered", blockHeader.getHash());
            }
            isValid = coinbaseInformation != null && coinbaseInformation.getWitnessMerkleRoot().equals(merkleRoot);
            logger.trace("witness merkle root is {} valid", (isValid ? "":"NOT"));
        }
        return isValid;
    }

    @VisibleForTesting
    protected boolean validationsForRegisterBtcTransaction(Sha256Hash btcTxHash, int height, byte[] pmtSerialized, byte[] btcTxSerialized)
            throws BlockStoreException, VerificationException.EmptyInputsOrOutputs, BridgeIllegalArgumentException {

        // Validates height and confirmations for tx
        try {
            int acceptableConfirmationsAmount = bridgeConstants.getBtc2RskMinimumAcceptableConfirmations();
            if (!BridgeUtils.validateHeightAndConfirmations(
                height,
                getBtcBlockchainBestChainHeight(),
                acceptableConfirmationsAmount,
                btcTxHash)) {
                return false;
            }
        } catch (Exception e) {
            String panicMessage = String.format("[validationsForRegisterBtcTransaction] Btc Tx %s Supplied Height is %d but should be greater than 0", btcTxHash, height);
            logger.warn(panicMessage);
            return false;
        }

        // Validates pmt size
        if (!PartialMerkleTreeFormatUtils.hasExpectedSize(pmtSerialized)) {
            String message = "PartialMerkleTree doesn't have expected size";
            logger.warn(message);
            throw new BridgeIllegalArgumentException(message);
        }

        // Calculates merkleRoot
        Sha256Hash merkleRoot;
        try {
            merkleRoot = BridgeUtils.calculateMerkleRoot(networkParameters, pmtSerialized, btcTxHash);
            if (merkleRoot == null) {
                return false;
            }
        } catch (VerificationException e) {
            throw new BridgeIllegalArgumentException(e.getMessage(), e);
        }

        // Validates inputs count
        logger.info("[validationsForRegisterBtcTransaction] Going to validate inputs for btc tx {}", btcTxHash);
        BridgeUtils.validateInputsCount(btcTxSerialized);

        // Check the merkle root equals merkle root of btc block at specified height in the btc best chain
        // BTC blockstore is available since we've already queried the best chain height
        logger.trace("[validationsForRegisterBtcTransaction] Getting btc block at height: {}", height);
        BtcBlock blockHeader = btcBlockStore.getStoredBlockAtMainChainHeight(height).getHeader();
        logger.trace("[validationsForRegisterBtcTransaction] Validating block merkle root at height: {}", height);
        if (!isBlockMerkleRootValid(merkleRoot, blockHeader)){
            String panicMessage = String.format(
                "[validationsForRegisterBtcTransaction] Btc Tx %s Supplied merkle root %s does not match block's merkle root %s",
                btcTxHash.toString(),
                merkleRoot,
                blockHeader.getMerkleRoot()
            );
            logger.warn(panicMessage);
            return false;
        }

        logger.trace("[validationsForRegisterBtcTransaction] Btc tx: {} successfully validated", btcTxHash);
        return true;
    }

    private Coin computeTotalAmountSent(BtcTransaction btcTx) {
        // Compute the total amount sent. Value could have been sent both to the
        // currently active federation and to the currently retiring federation.
        // Add both amounts up in that case.
        Coin amountToActive = btcTx.getValueSentToMe(getActiveFederationWallet());
        logger.debug("[computeTotalAmountSent] Amount sent to the active federation {}", amountToActive);

        Coin amountToRetiring = Coin.ZERO;
        Wallet retiringFederationWallet = getRetiringFederationWallet();
        if (retiringFederationWallet != null) {
            amountToRetiring = btcTx.getValueSentToMe(retiringFederationWallet);
        }
        logger.debug("[computeTotalAmountSent] Amount sent to the retiring federation {}", amountToRetiring);

        return amountToActive.add(amountToRetiring);
    }

    private static byte[] serializeBlockHeader(StoredBlock block) {
        if (block == null) {
            return new byte[0];
        }

        byte[] bytes = block.getHeader().unsafeBitcoinSerialize();

        byte[] header = new byte[80];

        System.arraycopy(bytes, 0, header, 0, 80);

        return header;
    }
}
