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

import static co.rsk.peg.federation.FederationFormatVersion.*;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.isNull;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.peg.bitcoin.CoinbaseInformation;
import co.rsk.peg.bitcoin.UtxoUtils;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.utils.HashOrdering;
import co.rsk.peg.utils.RskRlp;
import co.rsk.peg.vote.ABICallElection;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import com.google.common.primitives.UnsignedBytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Created by mario on 20/04/17.
 *
 * <p>Ported to Besu's RLP through {@link RskRlp}. Every encoding is byte for byte what RSKj produced, and every
 * decoder accepts what RSKj's accepted; the RSKj bytes recorded in BridgeSerializationUtilsTest pin the encodings,
 * and StorageLayoutFingerprintTest pins where they land in the bridge account.
 * The structure of each format is documented on its serializer, as in RSKj.
 */
public class BridgeSerializationUtils {
    private static final int FEDERATION_RLP_LIST_SIZE = 3;
    private static final int FEDERATION_CREATION_TIME_INDEX = 0;
    private static final int FEDERATION_CREATION_BLOCK_NUMBER_INDEX = 1;
    private static final int FEDERATION_MEMBERS_INDEX = 2;

    private BridgeSerializationUtils() {
        throw new IllegalAccessError("Utility class, do not instantiate it");
    }

    private static void writeRskTxHash(RLPOutput out, Hash rskTxHash) {
        RskRlp.writeElement(out, rskTxHash.getBytes().toArrayUnsafe());
    }

    public static Hash deserializeRskTxHash(byte[] rskTxHashSerialized) {
        if (isNull(rskTxHashSerialized)) {
            throw new IllegalArgumentException("Serialized hash cannot be null.");
        }
        return Hash.wrap(Bytes32.wrap(rskTxHashSerialized));
    }

    public static byte[] serializeOutpointsValues(List<Coin> outpointsValues) {
        return UtxoUtils.encodeOutpointValues(outpointsValues);
    }

    public static List<Coin> deserializeOutpointsValues(byte[] serializedOutpointsValues) {
        if (isNull(serializedOutpointsValues)) {
            throw new IllegalArgumentException("Serialized outpoints values cannot be null.");
        }
        return UtxoUtils.decodeOutpointValues(serializedOutpointsValues);
    }

    public static byte[] serializeBtcTransaction(BtcTransaction btcTransaction) {
        return RskRlp.encodeElement(btcTransaction.bitcoinSerialize());
    }

    private static void writeBtcTransaction(RLPOutput out, BtcTransaction btcTransaction) {
        RskRlp.writeElement(out, btcTransaction.bitcoinSerialize());
    }

    public static BtcTransaction deserializeBtcTransactionWithInputs(byte[] serializedTx, NetworkParameters networkParameters) {
        return deserializeBtcTransaction(serializedTx, networkParameters, true);
    }

    public static BtcTransaction deserializeBtcTransactionWithoutInputs(byte[] serializedTx, NetworkParameters networkParameters) {
        return deserializeBtcTransaction(serializedTx, networkParameters, false);
    }

    private static BtcTransaction deserializeBtcTransaction(
        byte[] serializedTx,
        NetworkParameters networkParameters,
        boolean txHasInputs
    ) {

        if (serializedTx == null || serializedTx.length == 0) {
            return null;
        }

        byte[] rawTx = RskRlp.readData(RskRlp.input(serializedTx));

        return deserializeBtcTransactionFromRawTx(rawTx, networkParameters, txHasInputs);
    }

    private static BtcTransaction deserializeBtcTransactionWithInputsFromRawTx(byte[] rawTx, NetworkParameters networkParameters) {
        return deserializeBtcTransactionFromRawTx(rawTx, networkParameters, true);
    }

    private static BtcTransaction deserializeBtcTransactionFromRawTx(
        byte[] rawTx,
        NetworkParameters networkParameters,
        boolean txHasInputs
    ) {

        if (!txHasInputs) {
            BtcTransaction tx = new BtcTransaction(networkParameters);
            tx.parseNoInputs(rawTx);
            return tx;
        }

        return new BtcTransaction(networkParameters, rawTx);
    }

    // An rsk tx waiting for signatures is serialized as a list [rskTxHash, btcTx];
    // a null entry is serialized as an empty list.
    public static byte[] serializeRskTxWaitingForSignatures(
          Map.Entry<Hash, BtcTransaction> rskTxWaitingForSignaturesEntry) {
        if (rskTxWaitingForSignaturesEntry == null) {
            return RskRlp.encodedEmptyList();
        }

        return RskRlp.encode(out -> {
            out.startList();
            writeRskTxWaitingForSignaturesEntry(out, rskTxWaitingForSignaturesEntry);
            out.endList();
        });
    }

    // A map of rsk txs waiting for signatures is serialized as the flat list
    // [rskTxHash_1, btcTx_1, ..., rskTxHash_n, btcTx_n], in the map's iteration order.
    public static byte[] serializeRskTxsWaitingForSignatures(
          SortedMap<Hash, BtcTransaction> rskTxWaitingForSignaturesMap) {

        return RskRlp.encode(out -> {
            out.startList();
            for (Map.Entry<Hash, BtcTransaction> rskTxWaitingForSignaturesEntry : rskTxWaitingForSignaturesMap.entrySet()) {
                writeRskTxWaitingForSignaturesEntry(out, rskTxWaitingForSignaturesEntry);
            }
            out.endList();
        });
    }

    private static void writeRskTxWaitingForSignaturesEntry(
          RLPOutput out, Map.Entry<Hash, BtcTransaction> rskTxWaitingForSignaturesEntry) {
        writeRskTxHash(out, rskTxWaitingForSignaturesEntry.getKey());
        writeBtcTransaction(out, rskTxWaitingForSignaturesEntry.getValue());
    }

    public static Map.Entry<Hash, BtcTransaction> deserializeRskTxWaitingForSignatures(
            byte[] data, NetworkParameters networkParameters) {
        if (data == null || data.length == 0) {
            return null;
        }

        RLPInput in = RskRlp.input(data);
        int size = in.enterList();
        if (size == 0) {
            return null;
        }
        return deserializeRskTxWaitingForSignaturesEntry(in, size, networkParameters);
    }

    public static SortedMap<Hash, BtcTransaction> deserializeRskTxsWaitingForSignatures(
            byte[] data, NetworkParameters networkParameters) {

        SortedMap<Hash, BtcTransaction> rskTxsWaitingForSignaturesMap = new TreeMap<>(HashOrdering.RSK);

        if (data == null || data.length == 0) {
            return rskTxsWaitingForSignaturesMap;
        }

        RLPInput in = RskRlp.input(data);
        int size = in.enterList();
        int numberOfRskTxsWaitingForSignatures = size / 2;

        for (int k = 0; k < numberOfRskTxsWaitingForSignatures; k++) {
            Map.Entry<Hash, BtcTransaction> rskTxWaitingForSignaturesEntry =
                deserializeRskTxWaitingForSignaturesEntry(in, size, networkParameters);

            rskTxsWaitingForSignaturesMap.put(rskTxWaitingForSignaturesEntry.getKey(), rskTxWaitingForSignaturesEntry.getValue());
        }

        return rskTxsWaitingForSignaturesMap;
    }

    private static Map.Entry<Hash, BtcTransaction> deserializeRskTxWaitingForSignaturesEntry(
            RLPInput in, int listSize, NetworkParameters networkParameters) {
        checkArgument(listSize > 0, "RLPList cannot be empty when deserializing an rsk tx WFS entry.");

        byte[] rskTxHashData = RskRlp.readData(in);
        Hash rskTxHash = deserializeRskTxHash(rskTxHashData);

        byte[] btcRawTx = RskRlp.readData(in);
        BtcTransaction btcTx = deserializeBtcTransactionWithInputsFromRawTx(btcRawTx, networkParameters);

        return new AbstractMap.SimpleEntry<>(rskTxHash, btcTx);
    }

    // A list of UTXOs is serialized as [utxo_1, ..., utxo_n], each in bitcoinj's UTXO stream format.
    public static byte[] serializeUTXOList(List<UTXO> list) {
        List<byte[]> serializedUtxos = new ArrayList<>(list.size());

        for (UTXO utxo : list) {
            try (ByteArrayOutputStream ostream = new ByteArrayOutputStream()) {
                utxo.serializeToStream(ostream);
                serializedUtxos.add(ostream.toByteArray());
            } catch (IOException ioe) {
                throw new SerializationException(String.format("Unable to serialize UTXO %s from UTXOs list %s", utxo, list), ioe);
            }
        }

        return RskRlp.encode(out -> {
            out.startList();
            for (byte[] serializedUtxo : serializedUtxos) {
                RskRlp.writeElement(out, serializedUtxo);
            }
            out.endList();
        });
    }

    public static List<UTXO> deserializeUTXOList(byte[] data) {
        List<UTXO> list = new ArrayList<>();

        if (data == null || data.length == 0) {
            return list;
        }

        RLPInput in = RskRlp.input(data);
        int nutxos = in.enterList();

        for (int k = 0; k < nutxos; k++) {
            byte[] utxoBytes = RskRlp.readData(in);
            InputStream istream = new ByteArrayInputStream(utxoBytes);
            try {
                UTXO utxo = new UTXO(istream);
                list.add(utxo);
            } catch (IOException ioe) {
                throw new SerializationException(String.format("Unable to deserialize %d th UTXO %s", k, Arrays.toString(data)), ioe);
            }
        }
        in.leaveList();

        return list;
    }

    // A map of hashes to longs is serialized as the flat list [hash_1, value_1, ..., hash_n, value_n],
    // with the hashes in their natural order.
    public static byte[] serializeMapOfHashesToLong(Map<Sha256Hash, Long> map) {
        List<Sha256Hash> sortedHashes = new ArrayList<>(map.keySet());
        Collections.sort(sortedHashes);

        return RskRlp.encode(out -> {
            out.startList();
            for (Sha256Hash hash : sortedHashes) {
                Long value = map.get(hash);
                RskRlp.writeElement(out, hash.getBytes());
                RskRlp.writeUnsigned(out, value);
            }
            out.endList();
        });
    }

    public static Map<Sha256Hash, Long> deserializeMapOfHashesToLong(byte[] data) {
        Map<Sha256Hash, Long> map = new HashMap<>();

        if (data == null || data.length == 0) {
            return map;
        }

        RLPInput in = RskRlp.input(data);
        int size = in.enterList();

        // List size must be even - key, value pairs expected in sequence
        if (size % 2 != 0) {
            throw new RuntimeException("deserializeMapOfHashesToLong: expected an even number of entries, but odd given");
        }

        int numEntries = size / 2;

        for (int k = 0; k < numEntries; k++) {
            Sha256Hash hash = Sha256Hash.wrap(RskRlp.readData(in));
            long number = RskRlp.readUnsigned(in).longValue();
            map.put(hash, number);
        }
        in.leaveList();

        return map;
    }

    private interface FederationMemberSerializer {
        byte[] serialize(FederationMember federationMember);
    }

    private interface FederationMemberDesserializer {
        FederationMember deserialize(byte[] data);
    }

    /**
     * A federation is serialized as a list in the following order:
     * - creation time
     * - creation block number
     * - list of federation members -> [member1, member2, ..., membern], sorted
     * using the lexicographical order of the public keys of the members
     * (see FederationMember.BTC_RSK_MST_PUBKEYS_COMPARATOR).
     * Each federation member is in turn serialized using the provided FederationMemberSerializer.
     */
    private static byte[] serializeFederationWithSerializer(Federation federation, FederationMemberSerializer federationMemberSerializer) {
        List<byte[]> federationMembers = federation.getMembers().stream()
            .sorted(FederationMember.BTC_RSK_MST_PUBKEYS_COMPARATOR)
            .map(federationMemberSerializer::serialize)
            .toList();

        return RskRlp.encode(out -> {
            out.startList();
            RskRlp.writeUnsigned(out, federation.getCreationTime().toEpochMilli());
            RskRlp.writeUnsigned(out, federation.getCreationBlockNumber());
            out.startList();
            for (byte[] federationMember : federationMembers) {
                RskRlp.writeElement(out, federationMember);
            }
            out.endList();
            out.endList();
        });
    }

    // For the serialization format, see BridgeSerializationUtils::serializeFederationWithSerializer
    private static StandardMultisigFederation deserializeStandardMultisigFederationWithDeserializer(
        byte[] data,
        NetworkParameters networkParameters,
        FederationMemberDesserializer federationMemberDesserializer) {

        RLPInput in = RskRlp.input(data);
        int size = in.enterList();

        if (size != FEDERATION_RLP_LIST_SIZE) {
            throw new RuntimeException(String.format("Invalid serialized Federation. Expected %d elements but got %d", FEDERATION_RLP_LIST_SIZE, size));
        }

        Instant creationTime = Instant.ofEpochMilli(RskRlp.readUnsigned(in).longValue());

        long creationBlockNumber = RskRlp.readUnsigned(in).longValue();

        int numberOfMembers = in.enterList();

        List<FederationMember> federationMembers = new ArrayList<>();

        for (int k = 0; k < numberOfMembers; k++) {
            FederationMember member = federationMemberDesserializer.deserialize(RskRlp.readData(in));
            federationMembers.add(member);
        }
        in.leaveList();
        in.leaveList();

        FederationArgs federationArgs = new FederationArgs(federationMembers, creationTime, creationBlockNumber, networkParameters);
        return FederationFactory.buildStandardMultiSigFederation(
            federationArgs
        );
    }

    /**
     * For the federation serialization format, see serializeFederationWithSerializer.
     * For the federation member serialization format, see serializeFederationMember.
     */
    public static byte[] serializeFederation(Federation federation) {
        return serializeFederationWithSerializer(
            federation,
            FederationMember::serialize
        );
    }

    public static Federation deserializeFederationAccordingToVersion(
        byte[] data,
        int version,
        FederationConstants federationConstants
    ) {
        NetworkParameters networkParameters = federationConstants.getBtcParams();
        if (version == STANDARD_MULTISIG_FEDERATION.getFormatVersion()) {
            return BridgeSerializationUtils.deserializeStandardMultisigFederation(
                data,
                networkParameters
            );
        }
        if (version == NON_STANDARD_ERP_FEDERATION.getFormatVersion()) {
            return BridgeSerializationUtils.deserializeNonStandardErpFederation(
                data,
                federationConstants
            );
        }
        if (version == P2SH_ERP_FEDERATION.getFormatVersion()) {
            return BridgeSerializationUtils.deserializeP2shErpFederation(
                data,
                federationConstants
            );
        }
        if (version == P2SH_P2WSH_ERP_FEDERATION.getFormatVersion()) {
            return BridgeSerializationUtils.deserializeP2shP2wshErpFederation(
                data,
                federationConstants
            );
        }
        // To keep backwards compatibility
        return BridgeSerializationUtils.deserializeStandardMultisigFederation(
            data,
            networkParameters
        );
    }

    // For the serialization format, see BridgeSerializationUtils::serializeFederation
    protected static StandardMultisigFederation deserializeStandardMultisigFederation(
        byte[] data,
        NetworkParameters networkParameters
    ) {
        return deserializeStandardMultisigFederationWithDeserializer(
            data,
            networkParameters,
            FederationMember::deserialize
        );
    }

    protected static ErpFederation deserializeNonStandardErpFederation(
        byte[] data,
        FederationConstants federationConstants
    ) {
        Federation federation = deserializeStandardMultisigFederationWithDeserializer(
            data,
            federationConstants.getBtcParams(),
            FederationMember::deserialize
        );
        FederationArgs federationArgs = federation.getArgs();
        List<BtcECKey> erpPubKeys = federationConstants.getErpFedPubKeysList();
        long activationDelay = federationConstants.getErpFedActivationDelay();
        return FederationFactory.buildNonStandardErpFederation(federationArgs, erpPubKeys, activationDelay);
    }

    protected static ErpFederation deserializeP2shErpFederation(
        byte[] data,
        FederationConstants federationConstants
    ) {
        Federation federation = deserializeStandardMultisigFederationWithDeserializer(
            data,
            federationConstants.getBtcParams(),
            FederationMember::deserialize
        );
        FederationArgs federationArgs = federation.getArgs();
        List<BtcECKey> erpPubKeys = federationConstants.getErpFedPubKeysList();
        long activationDelay = federationConstants.getErpFedActivationDelay();
        return FederationFactory.buildP2shErpFederation(federationArgs, erpPubKeys, activationDelay);
    }

    protected static ErpFederation deserializeP2shP2wshErpFederation(
        byte[] data,
        FederationConstants federationConstants
    ) {
        Federation federation = deserializeStandardMultisigFederationWithDeserializer(
            data,
            federationConstants.getBtcParams(),
            FederationMember::deserialize
        );
        FederationArgs federationArgs = federation.getArgs();
        List<BtcECKey> erpPubKeys = federationConstants.getErpFedPubKeysList();
        long activationDelay = federationConstants.getErpFedActivationDelay();
        return FederationFactory.buildP2shP2wshErpFederation(federationArgs, erpPubKeys, activationDelay);
    }

    // An ABI call election is serialized as a list of the votes, like so:
    // spec_1, voters_1, ..., spec_n, voters_n
    // Specs are sorted by their signed byte encoding lexicographically.
    public static byte[] serializeElection(ABICallElection election) {
        Map<ABICallSpec, List<org.hyperledger.besu.datatypes.Address>> votes = election.getVotes();
        ABICallSpec[] specs = votes.keySet().toArray(new ABICallSpec[0]);
        Arrays.sort(specs, ABICallSpec.byBytesComparator);

        return RskRlp.encode(out -> {
            out.startList();
            for (ABICallSpec spec : specs) {
                writeABICallSpec(out, spec);
                writeVoters(out, votes.get(spec));
            }
            out.endList();
        });
    }

    // For the serialization format, see BridgeSerializationUtils::serializeElection
    public static ABICallElection deserializeElection(byte[] data, AddressBasedAuthorizer authorizer) {
        if (data == null || data.length == 0) {
            return new ABICallElection(authorizer);
        }

        RLPInput in = RskRlp.input(data);
        int size = in.enterList();

        // List size must be even - key, value pairs expected in sequence
        if (size % 2 != 0) {
            throw new RuntimeException("deserializeElection: expected an even number of entries, but odd given");
        }

        int numEntries = size / 2;

        Map<ABICallSpec, List<org.hyperledger.besu.datatypes.Address>> votes = new HashMap<>();

        for (int k = 0; k < numEntries; k++) {
            ABICallSpec spec = deserializeABICallSpec(RskRlp.readData(in));
            List<org.hyperledger.besu.datatypes.Address> specVotes = deserializeVoters(RskRlp.readData(in));
            votes.put(spec, specVotes);
        }
        in.leaveList();

        return new ABICallElection(authorizer, votes);
    }

    public static byte[] serializeCoin(Coin coin) {
        return RskRlp.encode(out -> RskRlp.writeUnsigned(out, coin.getValue()));
    }

    @Nullable
    public static Coin deserializeCoin(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        return Coin.valueOf(RskRlp.readUnsigned(RskRlp.input(data)).longValueExact());
    }

    // A ReleaseRequestQueue is serialized as follows:
    // [address_1, amount_1, ..., address_n, amount_n]
    // with address_i being the encoded bytes of each btc address
    // and amount_i the RLP-encoded biginteger corresponding to each amount
    // Order of entries in serialized output is order of the request queue entries
    // so that we enforce a FIFO policy on release requests.
    public static byte[] serializeReleaseRequestQueue(ReleaseRequestQueue queue) {
        List<ReleaseRequestQueue.Entry> entries = queue.getEntriesWithoutHash();

        return RskRlp.encode(out -> {
            out.startList();
            for (ReleaseRequestQueue.Entry entry : entries) {
                RskRlp.writeElement(out, entry.getDestination().getHash160());
                RskRlp.writeUnsigned(out, entry.getAmount().getValue());
            }
            out.endList();
        });
    }

    // A ReleaseRequestQueue with tx hashes is serialized as follows:
    // [address_1, amount_1, rskTxHash_1, ..., address_n, amount_n, rskTxHash_n]
    public static byte[] serializeReleaseRequestQueueWithTxHash(ReleaseRequestQueue queue) {
        List<ReleaseRequestQueue.Entry> entries = queue.getEntriesWithHash();

        return RskRlp.encode(out -> {
            out.startList();
            for (ReleaseRequestQueue.Entry entry : entries) {
                RskRlp.writeElement(out, entry.getDestination().getHash160());
                RskRlp.writeUnsigned(out, entry.getAmount().getValue());
                writeRskTxHash(out, entry.getRskTxHash());
            }
            out.endList();
        });
    }

    public static List<ReleaseRequestQueue.Entry> deserializeReleaseRequestQueue(byte[] data, NetworkParameters networkParameters) {
        return deserializeReleaseRequestQueue(data, networkParameters, false);
    }

    public static List<ReleaseRequestQueue.Entry> deserializeReleaseRequestQueue(byte[] data, NetworkParameters networkParameters, boolean hasTxHash) {
        if (data == null || data.length == 0) {
            return new ArrayList<>();
        }

        int elementsMultipleCount = hasTxHash ? 3 : 2;
        RLPInput in = RskRlp.input(data);
        int size = in.enterList();

        // Must have an even number of items
        if (size % elementsMultipleCount != 0) {
            throw new RuntimeException(String.format("Invalid serialized ReleaseRequestQueue. Expected a multiple of %d number of elements, but got %d", elementsMultipleCount, size));
        }

        List<ReleaseRequestQueue.Entry> entries = hasTxHash ?
            deserializeReleaseRequestQueueWithTxHash(in, size, networkParameters) :
            deserializeReleaseRequestQueueWithoutTxHash(in, size, networkParameters);
        in.leaveList();
        return entries;
    }

    // For the serialization format, see BridgeSerializationUtils::serializeReleaseRequestQueue
    private static List<ReleaseRequestQueue.Entry> deserializeReleaseRequestQueueWithoutTxHash(RLPInput in, int size, NetworkParameters networkParameters) {
        List<ReleaseRequestQueue.Entry> entries = new ArrayList<>();

        int n = size / 2;
        for (int k = 0; k < n; k++) {
            byte[] addressBytes = RskRlp.readData(in);
            Address address = new Address(networkParameters, addressBytes);
            long amount = RskRlp.readUnsigned(in).longValue();
            entries.add(new ReleaseRequestQueue.Entry(address, Coin.valueOf(amount), null));
        }

        return entries;
    }

    // For the serialization format, see BridgeSerializationUtils::serializeReleaseRequestQueueWithTxHash
    private static List<ReleaseRequestQueue.Entry> deserializeReleaseRequestQueueWithTxHash(RLPInput in, int size, NetworkParameters networkParameters) {
        List<ReleaseRequestQueue.Entry> entries = new ArrayList<>();

        int n = size / 3;
        for (int k = 0; k < n; k++) {
            byte[] addressBytes = RskRlp.readData(in);
            Address address = new Address(networkParameters, addressBytes);
            long amount = RskRlp.readUnsigned(in).longValue();
            Hash txHash = deserializeRskTxHash(RskRlp.readData(in));
            entries.add(new ReleaseRequestQueue.Entry(address, Coin.valueOf(amount), txHash));
        }

        return entries;
    }

    // A PegoutsWaitingForConfirmations is serialized as follows:
    // [btctx_1, height_1, ..., btctx_n, height_n]
    // with btctx_i being the bitcoin serialization of each btc tx
    // and height_i the RLP-encoded biginteger corresponding to each height
    // To preserve order amongst different implementations of sets,
    // entries are first sorted on the lexicographical order of the
    // serialized btc transaction bytes
    // (see PegoutsWaitingForConfirmations.Entry.BTC_TX_COMPARATOR)
    public static byte[] serializePegoutsWaitingForConfirmations(PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations) {
        List<PegoutsWaitingForConfirmations.Entry> entries = new ArrayList<>(pegoutsWaitingForConfirmations.getEntriesWithoutHash());
        entries.sort(PegoutsWaitingForConfirmations.Entry.BTC_TX_COMPARATOR);

        return RskRlp.encode(out -> {
            out.startList();
            for (PegoutsWaitingForConfirmations.Entry entry : entries) {
                writeBtcTransaction(out, entry.getBtcTransaction());
                RskRlp.writeUnsigned(out, entry.getPegoutCreationRskBlockNumber());
            }
            out.endList();
        });
    }

    // As serializePegoutsWaitingForConfirmations, with the rsk tx hash after each height:
    // [btctx_1, height_1, rskTxHash_1, ..., btctx_n, height_n, rskTxHash_n]
    public static byte[] serializePegoutsWaitingForConfirmationsWithTxHash(PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations) {
        List<PegoutsWaitingForConfirmations.Entry> entries = new ArrayList<>(pegoutsWaitingForConfirmations.getEntriesWithHash());
        entries.sort(PegoutsWaitingForConfirmations.Entry.BTC_TX_COMPARATOR);

        return RskRlp.encode(out -> {
            out.startList();
            for (PegoutsWaitingForConfirmations.Entry entry : entries) {
                writeBtcTransaction(out, entry.getBtcTransaction());
                RskRlp.writeUnsigned(out, entry.getPegoutCreationRskBlockNumber());
                writeRskTxHash(out, entry.getPegoutCreationRskTxHash());
            }
            out.endList();
        });
    }

    public static PegoutsWaitingForConfirmations deserializePegoutsWaitingForConfirmations(byte[] data, NetworkParameters networkParameters) {
        return deserializePegoutsWaitingForConfirmations(data, networkParameters, false);
    }

    public static PegoutsWaitingForConfirmations deserializePegoutsWaitingForConfirmations(byte[] data, NetworkParameters networkParameters, boolean hasTxHash) {
        if (data == null || data.length == 0) {
            return new PegoutsWaitingForConfirmations(new HashSet<>());
        }

        int elementsMultipleCount = hasTxHash ? 3 : 2;
        RLPInput in = RskRlp.input(data);
        int size = in.enterList();

        // Must have an even number of items
        if (size % elementsMultipleCount != 0) {
            throw new RuntimeException(String.format("Invalid serialized pegoutsWaitingForConfirmations. Expected a multiple of %d number of elements, but got %d", elementsMultipleCount, size));
        }

        PegoutsWaitingForConfirmations pegouts = hasTxHash ?
            deserializePegoutWaitingForConfirmationsWithTxHash(in, size, networkParameters) :
            deserializePegoutsWaitingForConfirmationsWithoutTxHash(in, size, networkParameters);
        in.leaveList();
        return pegouts;
    }

    // For the serialization format, see BridgeSerializationUtils::serializePegoutsWaitingForConfirmations
    private static PegoutsWaitingForConfirmations deserializePegoutsWaitingForConfirmationsWithoutTxHash(RLPInput in, int size, NetworkParameters networkParameters) {
        Set<PegoutsWaitingForConfirmations.Entry> entries = new HashSet<>();

        int n = size / 2;
        for (int k = 0; k < n; k++) {
            byte[] txPayload = RskRlp.readData(in);
            BtcTransaction tx =  new BtcTransaction(networkParameters, txPayload);

            long height = RskRlp.readUnsigned(in).longValue();

            entries.add(new PegoutsWaitingForConfirmations.Entry(tx, height));
        }

        return new PegoutsWaitingForConfirmations(entries);
    }

    // For the serialization format, see BridgeSerializationUtils::serializePegoutsWaitingForConfirmationsWithTxHash
    private static PegoutsWaitingForConfirmations deserializePegoutWaitingForConfirmationsWithTxHash(RLPInput in, int size, NetworkParameters networkParameters) {
        Set<PegoutsWaitingForConfirmations.Entry> entries = new HashSet<>();

        int n = size / 3;
        for (int k = 0; k < n; k++) {
            byte[] txPayload = RskRlp.readData(in);
            BtcTransaction tx =  new BtcTransaction(networkParameters, txPayload);

            long height = RskRlp.readUnsigned(in).longValue();
            Hash rskTxHash = deserializeRskTxHash(RskRlp.readData(in));

            entries.add(new PegoutsWaitingForConfirmations.Entry(tx, height, rskTxHash));
        }

        return new PegoutsWaitingForConfirmations(entries);
    }

    public static byte[] serializeInteger(Integer value) {
        return RskRlp.encode(out -> RskRlp.writeUnsigned(out, value));
    }

    public static Integer deserializeInteger(byte[] data) {
        return RskRlp.readUnsigned(RskRlp.input(data)).intValue();
    }

    public static byte[] serializeLong(long value) {
        return RskRlp.encode(out -> RskRlp.writeUnsigned(out, value));
    }

    public static Optional<Long> deserializeOptionalLong(byte[] data) {
        if (data == null) {
            return Optional.empty();
        }
        return Optional.of(RskRlp.readUnsigned(RskRlp.input(data)).longValue());
    }

    // Coinbase information is serialized as the list [witnessMerkleRoot].
    public static CoinbaseInformation deserializeCoinbaseInformation(byte[] data) {
        if (data == null) {
            return null;
        }

        RLPInput in = RskRlp.input(data);
        int size = in.enterList();
        if (size != 1) {
            throw new RuntimeException(String.format("Invalid serialized coinbase information, expected 1 value but got %d", size));
        }

        Sha256Hash witnessMerkleRoot = Sha256Hash.wrap(RskRlp.readData(in));
        in.leaveList();
        return new CoinbaseInformation(witnessMerkleRoot);
    }

    public static byte[] serializeCoinbaseInformation(CoinbaseInformation coinbaseInformation) {
        if (coinbaseInformation == null) {
            return null;
        }
        return RskRlp.encode(out -> {
            out.startList();
            RskRlp.writeElement(out, coinbaseInformation.getWitnessMerkleRoot().getBytes());
            out.endList();
        });
    }

    public static byte[] serializeSha256Hash(Sha256Hash hash) {
        return RskRlp.encodeElement(hash.getBytes());
    }

    public static Sha256Hash deserializeSha256Hash(byte[] data) {
        if (data == null) {
            return null;
        }
        return Sha256Hash.wrap(RskRlp.readData(RskRlp.input(data)));
    }

    // A script is serialized as the list [program].
    public static byte[] serializeScript(Script script) {
        return RskRlp.encode(out -> {
            out.startList();
            RskRlp.writeElement(out, script.getProgram());
            out.endList();
        });
    }

    @Nullable
    public static Script deserializeScript(byte[] data) {
        if (data == null) {
            return null;
        }

        RLPInput in = RskRlp.input(data);
        int size = in.enterList();
        if (size != 1) {
            throw new RuntimeException(String.format("Invalid serialized script. Expected 1 element, but got %d", size));
        }

        Script script = new Script(RskRlp.readRawData(in));
        in.leaveList();
        return script;
    }


    // An ABI call spec is serialized as:
    // function name encoded in UTF-8
    // arg_1, ..., arg_n
    private static void writeABICallSpec(RLPOutput out, ABICallSpec spec) {
        out.startList();
        RskRlp.writeElement(out, spec.getFunction().getBytes(StandardCharsets.UTF_8));
        out.startList();
        for (byte[] argument : spec.getArguments()) {
            RskRlp.writeElement(out, argument);
        }
        out.endList();
        out.endList();
    }

    // For the serialization format, see BridgeSerializationUtils::writeABICallSpec
    private static ABICallSpec deserializeABICallSpec(byte[] data) {
        RLPInput in = RskRlp.input(data);
        int size = in.enterList();
        if (size != 2) {
            throw new RuntimeException(String.format("Invalid serialized ABICallSpec. Expected 2 elements, but got %d", size));
        }

        String function = new String(RskRlp.readData(in), StandardCharsets.UTF_8);
        int numberOfArguments = in.enterList();
        byte[][] arguments = new byte[numberOfArguments][];
        for (int k = 0; k < numberOfArguments; k++) {
            arguments[k] = RskRlp.readData(in);
        }
        in.leaveList();
        in.leaveList();

        return new ABICallSpec(function, arguments);
    }

    // A list of voters is serialized as
    // [voterBytes1, voterBytes2, ..., voterBytesn], sorted
    // using the lexicographical order of the voters' unsigned bytes
    private static void writeVoters(RLPOutput out, List<org.hyperledger.besu.datatypes.Address> voters) {
        List<org.hyperledger.besu.datatypes.Address> sortedVoters = voters.stream()
                .sorted(Comparator.comparing((org.hyperledger.besu.datatypes.Address a) -> a.getBytes().toArrayUnsafe(), UnsignedBytes.lexicographicalComparator()))
                .toList();
        out.startList();
        for (org.hyperledger.besu.datatypes.Address voter : sortedVoters) {
            RskRlp.writeElement(out, voter.getBytes().toArrayUnsafe());
        }
        out.endList();
    }

    // For the serialization format, see BridgeSerializationUtils::writeVoters
    private static List<org.hyperledger.besu.datatypes.Address> deserializeVoters(byte[] data) {
        RLPInput in = RskRlp.input(data);
        int size = in.enterList();
        List<org.hyperledger.besu.datatypes.Address> addresses = new ArrayList<>();
        for (int k = 0; k < size; k++) {
            org.hyperledger.besu.datatypes.Address address = org.hyperledger.besu.datatypes.Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(RskRlp.readData(in)));
            addresses.add(address);
        }
        in.leaveList();
        return addresses;
    }
}
