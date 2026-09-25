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

import co.rsk.peg.utils.PublicKeys;

import static co.rsk.peg.bitcoin.BitcoinUtils.inputHasWitness;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.crypto.TransactionSignature;
import co.rsk.bitcoinj.script.*;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.peg.bitcoin.BitcoinUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.bitcoin.RskAllowUnconfirmedCoinSelector;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationFormatVersion;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.constants.FeePerKbConstants;
import co.rsk.peg.utils.BtcTransactionFormatUtils;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import co.rsk.peg.host.CallContext;
import co.rsk.bitcoinj.core.BtcECKey;
import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Oscar Guindzberg
 */
public final class BridgeUtils {

    private static final Logger logger = LoggerFactory.getLogger(BridgeUtils.class);

    private BridgeUtils() {
    }

    public static Wallet getFederationNoSpendWallet(
        Context btcContext,
        Federation federation
    ) {
        return getFederationsNoSpendWallet(
            btcContext,
            Collections.singletonList(federation)
        );
    }

    public static Wallet getFederationsNoSpendWallet(
        Context btcContext,
        List<Federation> federations
    ) {
        Wallet wallet = new BridgeBtcWallet(btcContext, federations);

        federations.forEach(federation ->
            wallet.addWatchedAddress(
                federation.getAddress(),
                federation.getCreationTime().toEpochMilli()
            )
        );

        return wallet;
    }

    public static Wallet getFederationSpendWallet(
        Context btcContext,
        Federation federation,
        List<UTXO> utxos
    ) {
        return getFederationsSpendWallet(
            btcContext,
            Collections.singletonList(federation),
            utxos
        );
    }

    public static Wallet getFederationsSpendWallet(
        Context btcContext,
        List<Federation> federations,
        List<UTXO> utxos
    ) {
        Wallet wallet = new BridgeBtcWallet(btcContext, federations);

        RskUTXOProvider utxoProvider = new RskUTXOProvider(btcContext.getParams(), utxos);
        wallet.setUTXOProvider(utxoProvider);

        federations.forEach(federation ->
            wallet.addWatchedAddress(
                federation.getAddress(),
                federation.getCreationTime().toEpochMilli()
            )
        );

        wallet.setCoinSelector(new RskAllowUnconfirmedCoinSelector());
        return wallet;
    }

    /**
     * @param context
     * @param btcTx
     * @param addresses
     * @return total amount sent to the given list of addresses.
     */
    public static Coin getAmountSentToAddresses(
        Context context,
        BtcTransaction btcTx,
        List<Address> addresses
    ) {
        if (addresses == null || addresses.isEmpty()){
            return Coin.ZERO;
        }
        return getAmountSentToWallet(btcTx, createWatchedBtcWalletFromAddresses(context, addresses));
    }


    /**
     * @param context
     * @param addresses
     * @return a simple wallet instance with the give list of address added as watched addresses
     */
    private static WatchedBtcWallet createWatchedBtcWalletFromAddresses(Context context, List<Address> addresses) {
        WatchedBtcWallet wallet = new WatchedBtcWallet(context);
        long now = Utils.currentTimeMillis() / 1000L;
        wallet.addWatchedAddresses(addresses, now);
        return wallet;
    }

    /**
     *
     * @param btcTx
     * @param wallet
     * @return total amount sent to a given wallet.
     */
    private static Coin getAmountSentToWallet(BtcTransaction btcTx, Wallet wallet) {
        return btcTx.getValueSentToMe(wallet);
    }


    /**
     * @param context
     * @param btcTx
     * @param addresses
     * @return the list of UTXOs in the given btcTx sent to the given list of address
     */
    public static List<UTXO> getUTXOsSentToAddresses(Context context, BtcTransaction btcTx, List<Address> addresses) {
        Wallet wallet = BridgeUtils.createWatchedBtcWalletFromAddresses(context, addresses);
        return btcTx.getWalletOutputs(wallet).stream().map(
            txOutput -> new UTXO(
                btcTx.getHash(),
                txOutput.getIndex(),
                txOutput.getValue(),
                0,
                btcTx.isCoinBase(),
                txOutput.getScriptPubKey()
            )
        ).collect(Collectors.toList());
    }

    /**
     * Return the amount of missing signatures for a tx.
     * @param btcContext Btc context
     * @param btcTx The btc tx to check
     * @return 0 if was signed by the required number of federators, amount of missing signatures otherwise
     */
    public static int countMissingSignatures(Context btcContext, BtcTransaction btcTx) {
        Context.propagate(btcContext);

        // Check missing signatures for only one input as it is not
        // possible for a federator to leave unsigned inputs in a tx
        int inputIndex = 0;
        return countInputMissingSignatures(btcTx, inputIndex);
    }

    private static boolean isErpType(Script redeemScript) {
        List<ScriptChunk> redeemScriptChunks = redeemScript.getChunks();
        return RedeemScriptParserFactory.get(redeemScriptChunks).hasErpFormat();
    }

    /**
     * Checks whether a btc tx has been signed by the required number of federators.
     * @param btcContext Btc context
     * @param btcTx The btc tx to check
     * @return True if was signed by the required number of federators, false otherwise
     */
    public static boolean hasEnoughSignatures(Context btcContext, BtcTransaction btcTx) {
        // When the tx is constructed OP_0 are placed where signature should go.
        // Check all OP_0 have been replaced with actual signatures in all inputs
        Context.propagate(btcContext);

        for (int i = 0; i < btcTx.getInputs().size(); i ++) {
            if (countInputMissingSignatures(btcTx, i) > 0) {
                return false;
            }
        }
        return true;
    }

    private static int countInputMissingSignatures(BtcTransaction btcTx, int inputIndex) {
        Script redeemScript = BitcoinUtils.extractRedeemScriptFromInput(btcTx, inputIndex)
            .orElseThrow(IllegalArgumentException::new);

        if (!inputHasWitness(btcTx, inputIndex)) {
            return countInputScriptSigMissingSignatures(btcTx.getInput(inputIndex), redeemScript);
        }

        TransactionWitness inputWitness = btcTx.getWitness(inputIndex);
        return countInputWitnessMissingSignatures(inputWitness, redeemScript);
    }

    private static int countInputScriptSigMissingSignatures(TransactionInput input, Script redeemScript) {
        List<ScriptChunk> scriptSigChunks = input.getScriptSig().getChunks();

        int missingSigs = 0;
        int chunksToSubstract = countValuesToSubstract(redeemScript);
        int lastChunk = scriptSigChunks.size() - chunksToSubstract;
        for (int i = 1; i < lastChunk; i++) {
            ScriptChunk chunk = scriptSigChunks.get(i);
            if (!chunk.isOpCode() && chunk.data.length == 0) {
                missingSigs++;
            }
        }

        return missingSigs;
    }

    private static int countInputWitnessMissingSignatures(TransactionWitness inputWitness, Script redeemScript) {
        int missingSigs = 0;
        int pushesToSubstract = countValuesToSubstract(redeemScript);
        int lastPush = inputWitness.getPushCount() - pushesToSubstract;
        for (int i = 1; i < lastPush; i++) {
            byte[] push = inputWitness.getPush(i);
            if (push.length == 0) {
                missingSigs++;
            }
        }
        return missingSigs;
    }

    private static int countValuesToSubstract(Script redeemScript) {
        // when the redeem script is not erp, last value is for redeem script arg
        if (!isErpType(redeemScript)) {
            return 1;
        }

        // when the redeem script is erp, last values are for op_notif arg and redeem script arg
        return 2;
    }

    /**
     * The peg-out destination RSKj derived from the transaction signature: the P2PKH address of the
     * sender's compressed public key. The key reaches the bridge through the host.
     */
    public static Address recoverBtcAddressFromEthTransaction(CallContext tx, NetworkParameters networkParameters) {
        byte[] uncompressed = tx.getSenderPublicKey()
            .orElseThrow(() -> new IllegalStateException("The sender's public key is not available to derive the peg-out destination"));
        byte[] pubKey = PublicKeys.compressed(BtcECKey.fromPublicOnly(uncompressed));
        return BtcECKey.fromPublicOnly(pubKey).toAddress(networkParameters);
    }

    /** True for the addresses RSKj allowed to send bridge transactions for free during bootstrap. Used by the node-side rule. */
    public static boolean isFromAuthorizedSender(org.hyperledger.besu.datatypes.Address sender, BridgeConstants bridgeConstants) {
        FeePerKbConstants feePerKbConstants = bridgeConstants.getFeePerKbConstants();
        FederationConstants federationConstants = bridgeConstants.getFederationConstants();

        return isFromFederationChangeAuthorizedSender(sender, federationConstants) ||
            isFromFeePerKbChangeAuthorizedSender(sender, feePerKbConstants);
    }

    public static boolean isFromFederateMember(CallContext rskTx, Federation federation) {
        return federation.hasMemberWithRskAddress(rskTx.getSender().getBytes().toArrayUnsafe());
    }

    /**
     * Method that verify if an org.hyperledger.besu.datatypes.Address is part of the Genesis Federation
     *
     * @param rskAddress                  org.hyperledger.besu.datatypes.Address to find in Genesis Federation
     * @param genesisFederationPublicKeys List of BtcECKey part of Genesis Federation
     * @return boolean
     */
    public static boolean isFromGenesisFederation(org.hyperledger.besu.datatypes.Address rskAddress, List<BtcECKey> genesisFederationPublicKeys) {
        return genesisFederationPublicKeys.stream()
            .anyMatch(genesisBtcPublicKey -> PublicKeys.addressOf(genesisBtcPublicKey).equals(rskAddress));
    }

    public static Coin getCoinFromBigInteger(BigInteger value) throws BridgeIllegalArgumentException {
        if (value == null) {
            throw new BridgeIllegalArgumentException("value cannot be null");
        }
        try {
            return Coin.valueOf(value.longValueExact());
        } catch(ArithmeticException e) {
            throw new BridgeIllegalArgumentException(e.getMessage(), e);
        }
    }

    private static boolean isFromFederationChangeAuthorizedSender(org.hyperledger.besu.datatypes.Address sender, FederationConstants federationConstants) {
        AddressBasedAuthorizer authorizer = federationConstants.getFederationChangeAuthorizer();
        return authorizer.isAuthorized(sender);
    }

    private static boolean isFromFeePerKbChangeAuthorizedSender(org.hyperledger.besu.datatypes.Address sender, FeePerKbConstants feePerKbConstants) {
        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        return authorizer.isAuthorized(sender);
    }

    public static boolean validateHeightAndConfirmations(int height, int btcBestChainHeight, int acceptableConfirmationsAmount, Sha256Hash btcTxHash) throws Exception {
        // Check there are at least N blocks on top of the supplied height
        if (height < 0) {
            throw new Exception("Height can't be lower than 0");
        }
        int confirmations = btcBestChainHeight - height + 1;
        if (confirmations < acceptableConfirmationsAmount) {
            logger.warn(
                    "Btc Tx {} at least {} confirmations are required, but there are only {} confirmations",
                    btcTxHash,
                    acceptableConfirmationsAmount,
                    confirmations
            );
            return false;
        }
        return true;
    }

    public static Sha256Hash calculateMerkleRoot(NetworkParameters networkParameters, byte[] pmtSerialized, Sha256Hash btcTxHash) throws VerificationException{
        PartialMerkleTree pmt = new PartialMerkleTree(networkParameters, pmtSerialized, 0);
        List<Sha256Hash> hashesInPmt = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashesInPmt);
        if (!hashesInPmt.contains(btcTxHash)) {
            logger.warn("Supplied Btc Tx {} is not in the supplied partial merkle tree", btcTxHash);
            return null;
        }
        return merkleRoot;
    }

    public static void validateInputsCount(byte[] btcTxSerialized) throws VerificationException.EmptyInputsOrOutputs {
        if (BtcTransactionFormatUtils.getInputsCount(btcTxSerialized) == 0
            && BtcTransactionFormatUtils.getInputsCountForSegwit(btcTxSerialized) == 0) {
            logger.warn("Provided btc segwit tx has no inputs");
            // this is the exception thrown by co.rsk.bitcoinj.core.BtcTransaction#verify when there are no inputs.
            throw new VerificationException.EmptyInputsOrOutputs();
        }
    }

    /**
     * Check if the redeem data of the given input was already signed by federatorPublicKey.
     * @param btcTx the btc transaction
     * @param inputIndex The input index
     * @param federatorPublicKey The key that may have been used to sign
     * @param sighash the sighash that corresponds to the input
     * @return true if the input was already signed by the specified key, false otherwise.
     */
    public static boolean isInputSignedByThisFederator(BtcTransaction btcTx, int inputIndex, BtcECKey federatorPublicKey, Sha256Hash sighash) {
        if (!inputHasWitness(btcTx, inputIndex)) {
            Script inputScriptSig = btcTx.getInput(inputIndex).getScriptSig();
            return isInputScriptSigSignedByThisFederator(federatorPublicKey, sighash, inputScriptSig);
        }

        TransactionWitness inputWitness = btcTx.getWitness(inputIndex);
        return isInputWitnessSignedByThisFederator(federatorPublicKey, sighash, inputWitness);
    }

    private static boolean isInputScriptSigSignedByThisFederator(BtcECKey federatorPublicKey, Sha256Hash sighash, Script inputScriptSig) {
        List<ScriptChunk> chunks = inputScriptSig.getChunks();
        for (int j = 1; j < chunks.size() - 1; j++) {
            ScriptChunk chunk = chunks.get(j);

            if (chunk.data.length == 0) {
                continue;
            }

            if (signatureIsCorrect(federatorPublicKey, sighash, chunk.data)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInputWitnessSignedByThisFederator(BtcECKey federatorPublicKey, Sha256Hash sighash, TransactionWitness inputWitness) {
        for (int j = 1; j < inputWitness.getPushCount() - 1; j++) {
            byte[] push = inputWitness.getPush(j);

            if (push.length == 0) {
                continue;
            }

            if (signatureIsCorrect(federatorPublicKey, sighash, push)) {
                return true;
            }
        }
        return false;
    }

    private static boolean signatureIsCorrect(BtcECKey federatorPublicKey, Sha256Hash sigHash, byte[] data) {
        TransactionSignature decodedSignature = TransactionSignature.decodeFromBitcoin(data, false, false);
        return federatorPublicKey.verify(sigHash, decodedSignature);
    }

    public static byte[] serializeBtcAddressWithVersion(Address btcAddress) {
        byte[] hash160 = btcAddress.getHash160();
        // The version number is 1 byte only
        byte[] version = btcAddress.getVersion() != 0 ?
            Bytes.ofUnsignedInt(btcAddress.getVersion()).trimLeadingZeros().toArrayUnsafe() :
            new byte[]{0};

        byte[] btcAddressBytes = new byte[version.length + hash160.length];
        System.arraycopy(version, 0, btcAddressBytes, 0, version.length);
        System.arraycopy(hash160, 0, btcAddressBytes, version.length, hash160.length);

        return btcAddressBytes;
    }

    public static Address deserializeBtcAddressWithVersion(
        NetworkParameters networkParameters,
        byte[] addressBytes) throws BridgeIllegalArgumentException {

        // We expect 1 byte for the address version and 20 for the script hash / pub key hash
        if (addressBytes == null || addressBytes.length != 21) {
            throw new BridgeIllegalArgumentException("Invalid address, expected 21 bytes long array");
        }

        int version = addressBytes[0] & 0xFF;

        byte[] hashBytes = new byte[20];
        System.arraycopy(addressBytes, 1, hashBytes, 0, 20);

        return new Address(networkParameters, version, hashBytes);
    }

    public static int getRegularPegoutTxSize(@Nonnull Federation federation) {
        // A regular peg-out transaction has two inputs and two outputs
        // Each input has M/N signatures and each signature is around 71 bytes long (signed sighash)
        // The outputs are composed of the scriptPubkeyHas(or publicKeyHash)
        // and the op_codes for the corresponding script
        final int INPUT_MULTIPLIER = 2;
        final int OUTPUT_MULTIPLIER = 2;

        return calculatePegoutTxSize(
            federation,
            INPUT_MULTIPLIER,
            OUTPUT_MULTIPLIER
        );
    }

    public static int calculatePegoutTxSize(Federation federation, int inputsCount, int outputsCount) {

        if (inputsCount < 1 || outputsCount < 1) {
            throw new IllegalArgumentException("Inputs or outputs should be more than 1");
        }

        boolean isSegwit = federation.getFormatVersion() == FederationFormatVersion.P2SH_P2WSH_ERP_FEDERATION.getFormatVersion();

        return isSegwit
            ? calculateSegwitTxSize(federation, inputsCount, outputsCount)
            : calculateLegacyTxSize(federation, inputsCount, outputsCount);
    }

    private static int calculateLegacyTxSize(Federation federation, int inputsCount, int outputsCount) {
        BtcTransaction tx = new BtcTransaction(federation.getBtcParams());

        for (int i = 0; i < inputsCount; i++) {
            tx.addInput(Sha256Hash.ZERO_HASH, 0, federation.getRedeemScript());
        }

        for (int i = 0; i < outputsCount; i++) {
            tx.addOutput(Coin.ZERO, federation.getAddress());
        }

        int baseSize = calculateTxBaseSize(tx, inputsCount, false);
        int signingSize = getSigningSize(federation.getNumberOfSignaturesRequired(), inputsCount);
        return baseSize + signingSize;
    }

    private static int calculateSegwitTxSize(Federation federation, int inputsCount, int outputsCount) {
        BtcTransaction tx = new BtcTransaction(federation.getBtcParams());

        for (int i = 0; i < outputsCount; i++) {
            tx.addOutput(Coin.ZERO, federation.getAddress());
        }

        int baseSize = calculateTxBaseSize(tx, inputsCount, true);
        int signingSize = getSigningSize(federation.getNumberOfSignaturesRequired(), inputsCount);
        int totalSize = baseSize + signingSize + (inputsCount * federation.getRedeemScript().getProgram().length);
        // As described in BIP141
        int txWeight = totalSize + (3 * baseSize);
        return txWeight / 4;
    }

    private static int getSigningSize(int numberOfSignaturesRequired, int inputsCount) {
        int signatureSize = 72;
        return numberOfSignaturesRequired * inputsCount * signatureSize;
    }

    private static int calculateTxBaseSize(BtcTransaction tx, int inputsCount, boolean isSegwit) {
        int baseSize = tx.bitcoinSerialize().length;

        if (isSegwit) {
            final int SEGWIT_COMPATIBLE_SCRIPT_SIG_SIZE = 36;
            baseSize += inputsCount * SEGWIT_COMPATIBLE_SCRIPT_SIG_SIZE;
        }

        return baseSize;
    }

}
