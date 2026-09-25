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

import static co.rsk.peg.BridgeStorageIndexKey.*;
import static java.util.Objects.isNull;

import co.rsk.bitcoinj.core.*;
import co.rsk.peg.bitcoin.CoinbaseInformation;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.storage.StorageAccessor.RepositoryDeserializer;
import co.rsk.peg.storage.StorageAccessor.RepositorySerializer;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Provides an object oriented facade of the bridge contract memory.
 * @see co.rsk.peg.BridgeStorageProvider
 * @author ajlopez
 * @author Oscar Guindzberg
 *
 * <p>Ported over the shared {@link StorageAccessor}. The keys RSKj kept for formats that predate an activation
 * are gone: the processed-transactions map (each processed hash has its own key), and the request queue and
 * pegout set without transaction hashes (every entry on this chain carries one). Nothing here throws a checked
 * exception, since nothing under it can.
 */
public class BridgeStorageProvider {

    private static final Logger logger = LoggerFactory.getLogger(BridgeStorageProvider.class);

    // Dummy value to use when saving key only indexes
    private static final byte TRUE_VALUE = (byte) 1;

    private final StorageAccessor storage;
    private final NetworkParameters networkParameters;

    // RSK pegouts txs follow these steps: First, they are waiting for coin selection (releaseRequestQueue),
    // then they are waiting for enough confirmations on the RSK network (pegoutsWaitingForConfirmations),
    // then they are waiting for federators' signatures (pegoutsWaitingForSignatures),
    // then they are logged into the block that has them as completely signed for btc release
    // and are removed from pegoutsWaitingForSignatures.
    // key = rsk tx hash, value = btc tx
    private ReleaseRequestQueue releaseRequestQueue;
    private PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations;
    private SortedMap<Hash, BtcTransaction> pegoutsWaitingForSignatures;
    private SortedMap<Sha256Hash, List<Coin>> releasesOutpointsValues;

    private HashMap<Sha256Hash, Long> btcTxHashesToSave;

    private Map<Sha256Hash, CoinbaseInformation> coinbaseInformationMap;
    private Map<Integer, Sha256Hash> btcBlocksIndex;

    private long receiveHeadersLastTimestamp = 0;

    private Long nextPegoutHeight;

    private Set<Sha256Hash> pegoutTxSigHashes;

    private Sha256Hash svpFundTxHashUnsigned;
    private boolean isSvpFundTxHashUnsignedSet = false;
    private BtcTransaction svpFundTxSigned;
    private boolean isSvpFundTxSignedSet = false;
    private Sha256Hash svpSpendTxHashUnsigned;
    private boolean isSvpSpendTxHashUnsignedSet = false;
    private Map.Entry<Hash, BtcTransaction> svpSpendTxWaitingForSignatures;
    private boolean isSvpSpendTxWaitingForSignaturesSet = false;

    public BridgeStorageProvider(
        StorageAccessor storage,
        NetworkParameters networkParameters) {
        this.storage = storage;
        this.networkParameters = networkParameters;
    }

    public Optional<Long> getHeightIfBtcTxhashIsAlreadyProcessed(Sha256Hash btcTxHash) {
        if (btcTxHashesToSave == null) {
            btcTxHashesToSave = new HashMap<>();
        }

        if (btcTxHashesToSave.containsKey(btcTxHash)) {
            return Optional.of(btcTxHashesToSave.get(btcTxHash));
        }

        Optional<Long> height = storage.getFromRepository(getStorageKeyForBtcTxHashAlreadyProcessed(btcTxHash), BridgeSerializationUtils::deserializeOptionalLong);
        if (!height.isPresent()) {
            return height;
        }

        btcTxHashesToSave.put(btcTxHash, height.get());
        return height;
    }

    public void setHeightBtcTxhashAlreadyProcessed(Sha256Hash btcTxHash, long height) {
        if (btcTxHashesToSave == null) {
            btcTxHashesToSave = new HashMap<>();
        }
        btcTxHashesToSave.put(btcTxHash, height);
    }

    public void saveHeightBtcTxHashAlreadyProcessed() {
        if (btcTxHashesToSave == null) {
            return;
        }

        btcTxHashesToSave.forEach((btcTxHash, height) ->
            save(getStorageKeyForBtcTxHashAlreadyProcessed(btcTxHash), height, BridgeSerializationUtils::serializeLong)
        );
    }

    public ReleaseRequestQueue getReleaseRequestQueue() {
        if (releaseRequestQueue != null) {
            return releaseRequestQueue;
        }

        // Read only when something needs the requests themselves, which is the call that batches them
        releaseRequestQueue = new ReleaseRequestQueue(() -> new ArrayList<>(get(
            RELEASE_REQUEST_QUEUE_WITH_TXHASH,
            data -> BridgeSerializationUtils.deserializeReleaseRequestQueue(data, networkParameters, true)
        )));

        return releaseRequestQueue;
    }

    public void saveReleaseRequestQueue() {
        // Never read means never changed
        if (releaseRequestQueue == null || !releaseRequestQueue.isLoaded()) {
            return;
        }

        save(RELEASE_REQUEST_QUEUE_WITH_TXHASH, releaseRequestQueue, BridgeSerializationUtils::serializeReleaseRequestQueueWithTxHash);
    }

    public PegoutsWaitingForConfirmations getPegoutsWaitingForConfirmations() {
        if (pegoutsWaitingForConfirmations != null) {
            return pegoutsWaitingForConfirmations;
        }

        // The list is read only when something needs the entries themselves. A call that finds nothing confirmed
        // answers from the one slot below instead, which is most calls: a pegout is batched every few thousand
        // blocks and then waits tens of thousands more.
        pegoutsWaitingForConfirmations = new PegoutsWaitingForConfirmations(
            () -> new HashSet<>(get(
                PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY,
                data -> BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(data, networkParameters, true).getEntries())),
            getEarliestPegoutCreationBlock());

        return pegoutsWaitingForConfirmations;
    }

    /** Zero means the hint was never stored, which every reader must treat as "load the list and see". */
    private OptionalLong getEarliestPegoutCreationBlock() {
        UInt256 stored = storage.getSlot(earliestPegoutCreationBlockSlot());
        return stored.isZero() ? OptionalLong.empty() : OptionalLong.of(stored.toLong() - 1);
    }

    private static UInt256 earliestPegoutCreationBlockSlot() {
        return UInt256.fromBytes(Hash.hash(PEGOUTS_WAITING_FOR_CONFIRMATIONS_EARLIEST.getKey()).getBytes());
    }

    public void savePegoutsWaitingForConfirmations() {
        // Never read means never changed, and serializing it would defeat the point of not having read it
        if (pegoutsWaitingForConfirmations == null || !pegoutsWaitingForConfirmations.isLoaded()) {
            return;
        }

        save(PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY, pegoutsWaitingForConfirmations, BridgeSerializationUtils::serializePegoutsWaitingForConfirmationsWithTxHash);

        // The hint travels with the list it summarises, so the two can never disagree
        OptionalLong earliest = pegoutsWaitingForConfirmations.earliestCreationBlock();
        storage.putSlot(
            earliestPegoutCreationBlockSlot(),
            earliest.isPresent() ? UInt256.valueOf(earliest.getAsLong() + 1) : UInt256.ZERO);
    }

    public Optional<List<Coin>> getReleaseOutpointsValues(Sha256Hash releaseTxHash) {
        return Optional.ofNullable(releasesOutpointsValues)
            // search in cache
            .map(cachedValues -> cachedValues.get(releaseTxHash))
            // search in storage
            .or(() -> Optional.ofNullable(
                storage.getFromRepository(getStorageKeyForReleaseOutpointsValues(releaseTxHash), data -> data))
                .map(BridgeSerializationUtils::deserializeOutpointsValues)
            );
    }

    public void setReleaseOutpointsValues(Sha256Hash releaseTxHash, List<Coin> outpointsValues) {
        if (releaseTxHash == null || outpointsValues == null || outpointsValues.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("Invalid release outpoints values entry, has hash %s and coins list %s", releaseTxHash, outpointsValues)
            );
        }

        if (releaseTxHashStorageKeyAlreadyExists(releaseTxHash)) {
            throw new IllegalArgumentException("Release tx hash key already exists in storage.");
        }

        if (releasesOutpointsValues == null) {
            releasesOutpointsValues = new TreeMap<>();
        }
        releasesOutpointsValues.put(releaseTxHash, List.copyOf(outpointsValues));
    }

    private boolean releaseTxHashStorageKeyAlreadyExists(Sha256Hash releaseTxHash) {
        byte[] data = storage.getFromRepository(getStorageKeyForReleaseOutpointsValues(releaseTxHash), value -> value);

        return data != null;
    }

    private void saveReleasesOutpointsValues() {
        if (isNull(releasesOutpointsValues)) {
            return;
        }

        releasesOutpointsValues.forEach(
            (releaseTxHash, outpointsValues) -> save(
                getStorageKeyForReleaseOutpointsValues(releaseTxHash),
                outpointsValues,
                BridgeSerializationUtils::serializeOutpointsValues
            )
        );
    }

    public SortedMap<Hash, BtcTransaction> getPegoutsWaitingForSignatures() {
        if (pegoutsWaitingForSignatures != null) {
            return pegoutsWaitingForSignatures;
        }

        pegoutsWaitingForSignatures = get(
            PEGOUTS_WAITING_FOR_SIGNATURES,
            data -> BridgeSerializationUtils.deserializeRskTxsWaitingForSignatures(data, networkParameters)
        );
        return pegoutsWaitingForSignatures;
    }

    private void savePegoutsWaitingForSignatures() {
        if (pegoutsWaitingForSignatures == null) {
            return;
        }

        save(PEGOUTS_WAITING_FOR_SIGNATURES, pegoutsWaitingForSignatures, BridgeSerializationUtils::serializeRskTxsWaitingForSignatures);
    }

    public CoinbaseInformation getCoinbaseInformation(Sha256Hash blockHash) {
        if (coinbaseInformationMap == null) {
            coinbaseInformationMap = new HashMap<>();
        }

        if (coinbaseInformationMap.containsKey(blockHash)) {
            return coinbaseInformationMap.get(blockHash);
        }

        CoinbaseInformation coinbaseInformation =
                storage.getFromRepository(getStorageKeyForCoinbaseInformation(blockHash), BridgeSerializationUtils::deserializeCoinbaseInformation);
        coinbaseInformationMap.put(blockHash, coinbaseInformation);

        return coinbaseInformation;
    }

    public void setCoinbaseInformation(Sha256Hash blockHash, CoinbaseInformation data) {
        if (coinbaseInformationMap == null) {
            coinbaseInformationMap = new HashMap<>();
        }

        coinbaseInformationMap.put(blockHash, data);
    }

    public Optional<Sha256Hash> getBtcBestBlockHashByHeight(int height) {
        Bytes32 storageKey = getStorageKeyForBtcBlockIndex(height);
        Sha256Hash blockHash = storage.getFromRepository(storageKey, BridgeSerializationUtils::deserializeSha256Hash);
        if (blockHash != null) {
            return Optional.of(blockHash);
        }

        return Optional.empty();
    }

    public void setBtcBestBlockHashByHeight(int height, Sha256Hash blockHash) {
        if (btcBlocksIndex == null) {
            btcBlocksIndex = new HashMap<>();
        }

        btcBlocksIndex.put(height, blockHash);
    }

    private void saveBtcBlocksIndex() {
        if (btcBlocksIndex != null) {
            btcBlocksIndex.forEach((Integer height, Sha256Hash blockHash) -> {
                Bytes32 storageKey = getStorageKeyForBtcBlockIndex(height);
                save(storageKey, blockHash, BridgeSerializationUtils::serializeSha256Hash);
            });
        }
    }

    private void saveCoinbaseInformations() {
        if (coinbaseInformationMap == null || coinbaseInformationMap.isEmpty()) {
            return;
        }
        coinbaseInformationMap.forEach((Sha256Hash blockHash, CoinbaseInformation data) ->
            save(getStorageKeyForCoinbaseInformation(blockHash), data, BridgeSerializationUtils::serializeCoinbaseInformation));
    }


    public Optional<Long> getReceiveHeadersLastTimestamp() {
        return get(
            RECEIVE_HEADERS_TIMESTAMP,
            BridgeSerializationUtils::deserializeOptionalLong
        );
    }

    public void setReceiveHeadersLastTimestamp(Long timeInMillis) {
        receiveHeadersLastTimestamp = timeInMillis;
    }

    public void saveReceiveHeadersLastTimestamp() {
        if (this.receiveHeadersLastTimestamp > 0) {
            save(RECEIVE_HEADERS_TIMESTAMP, this.receiveHeadersLastTimestamp, BridgeSerializationUtils::serializeLong);
        }
    }

    public Optional<Long> getNextPegoutHeight() {
        if (nextPegoutHeight == null) {
            nextPegoutHeight = get(NEXT_PEGOUT_HEIGHT_KEY, BridgeSerializationUtils::deserializeOptionalLong).orElse(0L);
        }

        return Optional.of(nextPegoutHeight);
    }

    public void setNextPegoutHeight(long nextPegoutHeight) {
        this.nextPegoutHeight = nextPegoutHeight;
    }

    protected void saveNextPegoutHeight() {
        if (nextPegoutHeight == null) {
            return;
        }

        save(NEXT_PEGOUT_HEIGHT_KEY, nextPegoutHeight, BridgeSerializationUtils::serializeLong);
    }

    protected int getReleaseRequestQueueSize() {
        return getReleaseRequestQueue().getEntries().size();
    }

    public boolean hasPegoutTxSigHash(Sha256Hash sigHash) {
        if (sigHash == null){
            return false;
        }

        byte[] data = storage.getFromRepository(getStorageKeyForPegoutTxSigHash(sigHash), value -> value);

        return data != null &&
           data.length == 1 &&
           data[0] == TRUE_VALUE;
    }

    public void setPegoutTxSigHash(Sha256Hash sigHash) {
        if (sigHash == null) {
            return;
        }

        if (hasPegoutTxSigHash(sigHash)){
            throw new IllegalStateException(String.format("Given pegout tx sigHash %s already exists in the index. Index entries are considered unique.", sigHash));
        }

        if (pegoutTxSigHashes == null){
            pegoutTxSigHashes = new HashSet<>();
        }

        pegoutTxSigHashes.add(sigHash);
    }

    protected void savePegoutTxSigHashes() {
        if (pegoutTxSigHashes == null) {
            return;
        }

        pegoutTxSigHashes.forEach(pegoutTxSigHash -> storage.saveToRepository(getStorageKeyForPegoutTxSigHash(
                pegoutTxSigHash
            ),
            new byte[]{TRUE_VALUE}
        ));
    }

    public Optional<Sha256Hash> getSvpFundTxHashUnsigned() {
        if (svpFundTxHashUnsigned != null) {
            return Optional.of(svpFundTxHashUnsigned);
        }

        // Return empty if the svp fund tx hash unsigned was explicitly set to null
        if (isSvpFundTxHashUnsignedSet) {
            return Optional.empty();
        }

        svpFundTxHashUnsigned = get(SVP_FUND_TX_HASH_UNSIGNED, BridgeSerializationUtils::deserializeSha256Hash);
        return Optional.ofNullable(svpFundTxHashUnsigned);
    }

    public Optional<BtcTransaction> getSvpFundTxSigned() {
        if (svpFundTxSigned != null) {
            return Optional.of(svpFundTxSigned);
        }

        // Return empty if the svp fund tx signed was explicitly set to null
        if (isSvpFundTxSignedSet) {
            return Optional.empty();
        }

        svpFundTxSigned = get(SVP_FUND_TX_SIGNED,
            data -> BridgeSerializationUtils.deserializeBtcTransactionWithInputs(data, networkParameters));
        return Optional.ofNullable(svpFundTxSigned);
    }

    public Optional<Sha256Hash> getSvpSpendTxHashUnsigned() {
        if (svpSpendTxHashUnsigned != null) {
            return Optional.of(svpSpendTxHashUnsigned);
        }

        // Return empty if the svp spend tx hash unsigned was explicitly set to null
        if (isSvpSpendTxHashUnsignedSet) {
            return Optional.empty();
        }

        svpSpendTxHashUnsigned = get(SVP_SPEND_TX_HASH_UNSIGNED, BridgeSerializationUtils::deserializeSha256Hash);
        return Optional.ofNullable(svpSpendTxHashUnsigned);
    }

    public Optional<Map.Entry<Hash, BtcTransaction>> getSvpSpendTxWaitingForSignatures() {
        if (svpSpendTxWaitingForSignatures != null) {
            return Optional.of(svpSpendTxWaitingForSignatures);
        }

        // Return empty if the svp spend tx waiting for signatures was explicitly set to null
        if (isSvpSpendTxWaitingForSignaturesSet) {
            return Optional.empty();
        }

        svpSpendTxWaitingForSignatures = get(
            SVP_SPEND_TX_WAITING_FOR_SIGNATURES,
            data -> BridgeSerializationUtils.deserializeRskTxWaitingForSignatures(data, networkParameters));
        return Optional.ofNullable(svpSpendTxWaitingForSignatures);
    }

    public void setSvpFundTxHashUnsigned(Sha256Hash hash) {
        this.svpFundTxHashUnsigned = hash;
        this.isSvpFundTxHashUnsignedSet = true;
    }

    public void clearSvpFundTxHashUnsigned() {
        logger.info("[clearSvpFundTxHashUnsigned] Clearing fund tx hash unsigned.");
        setSvpFundTxHashUnsigned(null);
    }

    private void saveSvpFundTxHashUnsigned() {
        if (!isSvpFundTxHashUnsignedSet) {
            return;
        }

        save(
            SVP_FUND_TX_HASH_UNSIGNED,
            svpFundTxHashUnsigned,
            BridgeSerializationUtils::serializeSha256Hash
        );
    }

    public void setSvpFundTxSigned(BtcTransaction svpFundTxSigned) {
        this.svpFundTxSigned = svpFundTxSigned;
        this.isSvpFundTxSignedSet = true;
    }

    public void clearSvpFundTxSigned() {
        logger.info("[clearSvpFundTxSigned] Clearing fund tx signed.");
        setSvpFundTxSigned(null);
    }

    private void saveSvpFundTxSigned() {
        if (!isSvpFundTxSignedSet) {
            return;
        }

        save(
            SVP_FUND_TX_SIGNED,
            svpFundTxSigned,
            BridgeSerializationUtils::serializeBtcTransaction);
    }

    public void setSvpSpendTxHashUnsigned(Sha256Hash hash) {
        this.svpSpendTxHashUnsigned = hash;
        this.isSvpSpendTxHashUnsignedSet = true;
    }

    public void clearSvpSpendTxHashUnsigned() {
        logger.info("[clearSvpSpendTxHashUnsigned] Clearing spend tx hash unsigned.");
        setSvpSpendTxHashUnsigned(null);
    }

    private void saveSvpSpendTxHashUnsigned() {
        if (!isSvpSpendTxHashUnsignedSet) {
            return;
        }

        save(
            SVP_SPEND_TX_HASH_UNSIGNED,
            svpSpendTxHashUnsigned,
            BridgeSerializationUtils::serializeSha256Hash);
    }

    public void setSvpSpendTxWaitingForSignatures(Map.Entry<Hash, BtcTransaction> svpSpendTxWaitingForSignatures) {
        boolean hasNullKeyOrValue = svpSpendTxWaitingForSignatures != null &&
            (svpSpendTxWaitingForSignatures.getKey() == null || svpSpendTxWaitingForSignatures.getValue() == null);

        if (hasNullKeyOrValue) {
            throw new IllegalArgumentException(
                String.format("Invalid svpSpendTxWaitingForSignatures, has null key or value: %s", svpSpendTxWaitingForSignatures)
            );
        }

        this.svpSpendTxWaitingForSignatures = svpSpendTxWaitingForSignatures;
        this.isSvpSpendTxWaitingForSignaturesSet = true;
    }

    public void clearSvpSpendTxWaitingForSignatures() {
        logger.info("[clearSvpSpendTxWaitingForSignatures] Clearing spend tx waiting for signatures.");
        setSvpSpendTxWaitingForSignatures(null);
    }

    private void saveSvpSpendTxWaitingForSignatures() {
        if (!isSvpSpendTxWaitingForSignaturesSet) {
            return;
        }

        save(
            SVP_SPEND_TX_WAITING_FOR_SIGNATURES,
            svpSpendTxWaitingForSignatures,
            BridgeSerializationUtils::serializeRskTxWaitingForSignatures
        );
    }

    public void clearSvpValues() {
        logger.info("[clearSvpValues] Clearing all SVP values.");

        clearSvpFundTxHashUnsigned();
        clearSvpFundTxSigned();
        clearSvpSpendTxWaitingForSignatures();
        clearSvpSpendTxHashUnsigned();
    }

    public void save() {
        saveReleaseRequestQueue();
        savePegoutsWaitingForConfirmations();
        savePegoutsWaitingForSignatures();

        saveHeightBtcTxHashAlreadyProcessed();

        saveCoinbaseInformations();

        saveBtcBlocksIndex();


        saveReceiveHeadersLastTimestamp();

        saveNextPegoutHeight();

        savePegoutTxSigHashes();

        saveReleasesOutpointsValues();

        saveSvpFundTxHashUnsigned();
        saveSvpFundTxSigned();
        saveSvpSpendTxHashUnsigned();
        saveSvpSpendTxWaitingForSignatures();
    }

    private Bytes32 getStorageKeyForBtcTxHashAlreadyProcessed(Sha256Hash btcTxHash) {
        return BTC_TX_HASH_AP.getCompoundKey("-", btcTxHash.toString());
    }

    private Bytes32 getStorageKeyForCoinbaseInformation(Sha256Hash btcTxHash) {
        return COINBASE_INFORMATION.getCompoundKey("-", btcTxHash.toString());
    }

    private Bytes32 getStorageKeyForBtcBlockIndex(Integer height) {
        return BTC_BLOCK_HEIGHT.getCompoundKey("-", height.toString());
    }


    private Bytes32 getStorageKeyForPegoutTxSigHash(Sha256Hash sigHash) {
        return PEGOUT_TX_SIG_HASH.getCompoundKey("-", sigHash.toString());
    }

    private Bytes32 getStorageKeyForReleaseOutpointsValues(Sha256Hash releaseTxHash) {
        return RELEASES_OUTPOINTS_VALUES.getCompoundKey("-", releaseTxHash.toString());
    }

    private <T> T get(BridgeStorageIndexKey key, RepositoryDeserializer<T> deserializer) {
        return storage.getFromRepository(key.getKey(), deserializer);
    }

    private <T> void save(BridgeStorageIndexKey key, T object, RepositorySerializer<T> serializer) {
        save(key.getKey(), object, serializer);
    }

    private <T> void save(Bytes32 key, T object, RepositorySerializer<T> serializer) {
        storage.saveToRepository(key, object, serializer);
    }
}
