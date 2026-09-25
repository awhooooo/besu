package co.rsk.peg.federation;

import co.rsk.bitcoinj.core.UTXO;
import co.rsk.bitcoinj.script.Script;
import co.rsk.peg.BridgeSerializationUtils;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.storage.RecordBackedUtxoList;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.storage.UtxoRecords;
import co.rsk.peg.vote.ABICallElection;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static co.rsk.peg.federation.FederationStorageIndexKey.*;
import static co.rsk.peg.federation.FederationFormatVersion.*;

/**
 * Federation state over the shared storage accessor.
 *
 * <p>The two UTXO lists are {@link UtxoRecords}: written through as they change, one key for every network.
 * Everything else is a blob saved by {@link #save}. Federations are always stored with their format version;
 * a stored federation without one is corrupt state and is rejected, since on this chain no federation was ever
 * written in the versionless format RSKj used before RSKIP123.
 */
public class FederationStorageProviderImpl implements FederationStorageProvider {
    private static final Logger logger = LoggerFactory.getLogger(FederationStorageProviderImpl.class);
    private final StorageAccessor bridgeStorageAccessor;
    private final HashMap<Bytes32, Optional<Integer>> storageVersionEntries;

    private RecordBackedUtxoList newFederationBtcUTXOs;
    private RecordBackedUtxoList oldFederationBtcUTXOs;
    private Federation newFederation;
    private Federation oldFederation;
    private boolean shouldSaveOldFederation = false;

    private PendingFederation pendingFederation;
    private boolean shouldSavePendingFederation = false;

    private Federation proposedFederation;
    private boolean isProposedFederationSet = false;

    private ABICallElection federationElection;

    private Long activeFederationCreationBlockHeight;
    private Long nextFederationCreationBlockHeight; // if -1, then clear value

    private Script lastRetiredFederationP2SHScript;

    public FederationStorageProviderImpl(StorageAccessor bridgeStorageAccessor) {
        this.bridgeStorageAccessor = bridgeStorageAccessor;
        this.storageVersionEntries = new HashMap<>();
    }

    @Override
    public List<UTXO> getNewFederationBtcUTXOs() {
        if (newFederationBtcUTXOs == null) {
            newFederationBtcUTXOs = new RecordBackedUtxoList(new UtxoRecords(bridgeStorageAccessor, NEW_FEDERATION_BTC_UTXOS_KEY.getKey()));
        }
        return newFederationBtcUTXOs;
    }

    @Override
    public List<UTXO> getOldFederationBtcUTXOs() {
        if (oldFederationBtcUTXOs == null) {
            oldFederationBtcUTXOs = new RecordBackedUtxoList(new UtxoRecords(bridgeStorageAccessor, OLD_FEDERATION_BTC_UTXOS_KEY.getKey()));
        }
        return oldFederationBtcUTXOs;
    }

    @Override
    public Federation getNewFederation(FederationConstants federationConstants) {
        if (newFederation != null) {
            return newFederation;
        }

        Optional<Integer> storageVersion = getStorageVersion(NEW_FEDERATION_FORMAT_VERSION.getKey());

        newFederation = bridgeStorageAccessor.getFromRepository(
            NEW_FEDERATION_KEY.getKey(),
            data -> {
                if (data == null) {
                    return null;
                }
                return BridgeSerializationUtils.deserializeFederationAccordingToVersion(data, requireVersion(storageVersion, "new federation"), federationConstants);
            }
        );

        return newFederation;
    }

    private Optional<Integer> getStorageVersion(Bytes32 versionKey) {
        if (storageVersionEntries.containsKey(versionKey)) {
            return storageVersionEntries.get(versionKey);
        }

        Optional<Integer> version = bridgeStorageAccessor.getFromRepository(versionKey, data -> {
            if (data == null || data.length == 0) {
                return Optional.empty();
            }

            return Optional.of(BridgeSerializationUtils.deserializeInteger(data));
        });
        storageVersionEntries.put(versionKey, version);
        return version;
    }

    private static int requireVersion(Optional<Integer> storageVersion, String what) {
        return storageVersion.orElseThrow(() -> {
            String message = "Storage version should be present for a stored " + what;
            logger.warn("[requireVersion] {}", message);
            return new IllegalStateException(message);
        });
    }

    @Override
    public void setNewFederation(Federation federation) {
        newFederation = federation;
    }

    @Override
    public Federation getOldFederation(FederationConstants federationConstants) {
        if (oldFederation != null || shouldSaveOldFederation) {
            return oldFederation;
        }

        Optional<Integer> storageVersion = getStorageVersion(OLD_FEDERATION_FORMAT_VERSION.getKey());

        oldFederation = bridgeStorageAccessor.getFromRepository(
            OLD_FEDERATION_KEY.getKey(),
            data -> {
                if (data == null) {
                    return null;
                }
                return BridgeSerializationUtils.deserializeFederationAccordingToVersion(data, requireVersion(storageVersion, "old federation"), federationConstants);
            }
        );

        return oldFederation;
    }

    @Override
    public void setOldFederation(Federation federation) {
        shouldSaveOldFederation = true;
        oldFederation = federation;
    }

    @Override
    public PendingFederation getPendingFederation() {
        if (pendingFederation != null || shouldSavePendingFederation) {
            return pendingFederation;
        }

        Optional<Integer> storageVersion = getStorageVersion(PENDING_FEDERATION_FORMAT_VERSION.getKey());

        pendingFederation =
            bridgeStorageAccessor.getFromRepository(PENDING_FEDERATION_KEY.getKey(),
                data -> {
                    if (data == null) {
                        return null;
                    }
                    requireVersion(storageVersion, "pending federation");
                    return PendingFederation.deserialize(data);
                }
            );

        return pendingFederation;
    }

    @Override
    public void setPendingFederation(PendingFederation federation) {
        shouldSavePendingFederation = true;
        pendingFederation = federation;
    }

    @Override
    public void setProposedFederation(Federation proposedFederation) {
        this.proposedFederation = proposedFederation;
        isProposedFederationSet = true;
    }

    @Override
    public Optional<Federation> getProposedFederation(FederationConstants federationConstants) {
        if (proposedFederation != null) {
            return Optional.of(proposedFederation);
        }

        // reaching this point means the proposed federation was set to null
        if (isProposedFederationSet) {
            return Optional.empty();
        }

        proposedFederation = bridgeStorageAccessor.getFromRepository(
            PROPOSED_FEDERATION.getKey(),
            data -> {
                if (data == null) {
                    return null;
                }

                Optional<Integer> storageVersion = getStorageVersion(PROPOSED_FEDERATION_FORMAT_VERSION.getKey());
                return BridgeSerializationUtils.deserializeFederationAccordingToVersion(data, requireVersion(storageVersion, "proposed federation"), federationConstants);
            }
        );

        return Optional.ofNullable(proposedFederation);
    }

    @Override
    public ABICallElection getFederationElection(AddressBasedAuthorizer authorizer) {
        if (federationElection != null) {
            return federationElection;
        }

        federationElection = bridgeStorageAccessor.getFromRepository(FEDERATION_ELECTION_KEY.getKey(), data -> (data == null)? new ABICallElection(authorizer) : BridgeSerializationUtils.deserializeElection(data, authorizer));
        return federationElection;
    }

    @Override
    public Optional<Long> getActiveFederationCreationBlockHeight() {
        if (activeFederationCreationBlockHeight != null) {
            return Optional.of(activeFederationCreationBlockHeight);
        }

        activeFederationCreationBlockHeight = bridgeStorageAccessor.getFromRepository(ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), BridgeSerializationUtils::deserializeOptionalLong).orElse(null);
        return Optional.ofNullable(activeFederationCreationBlockHeight);
    }

    @Override
    public void setActiveFederationCreationBlockHeight(long activeFederationCreationBlockHeight) {
        this.activeFederationCreationBlockHeight = activeFederationCreationBlockHeight;
    }

    @Override
    public Optional<Long> getNextFederationCreationBlockHeight() {
        if (nextFederationCreationBlockHeight != null) {
            return Optional.of(nextFederationCreationBlockHeight);
        }

        nextFederationCreationBlockHeight = bridgeStorageAccessor.getFromRepository(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), BridgeSerializationUtils::deserializeOptionalLong).orElse(null);
        return Optional.ofNullable(nextFederationCreationBlockHeight);
    }

    @Override
    public void setNextFederationCreationBlockHeight(long nextFederationCreationBlockHeight) {
        this.nextFederationCreationBlockHeight = nextFederationCreationBlockHeight;
    }

    @Override
    public void clearNextFederationCreationBlockHeight() {
        this.nextFederationCreationBlockHeight = -1L;
    }

    @Override
    public Optional<Script> getLastRetiredFederationP2SHScript() {
        if (lastRetiredFederationP2SHScript != null) {
            return Optional.of(lastRetiredFederationP2SHScript);
        }

        lastRetiredFederationP2SHScript = bridgeStorageAccessor.getFromRepository(LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY.getKey(), BridgeSerializationUtils::deserializeScript);
        return Optional.ofNullable(lastRetiredFederationP2SHScript);
    }

    @Override
    public void setLastRetiredFederationP2SHScript(Script lastRetiredFederationP2SHScript) {
        this.lastRetiredFederationP2SHScript = lastRetiredFederationP2SHScript;
    }

    /** The UTXO lists are written through and need no saving here. */
    @Override
    public void save() {
        saveNewFederation();
        saveOldFederation();

        savePendingFederation();
        saveProposedFederation();

        saveFederationElection();

        saveActiveFederationCreationBlockHeight();

        saveNextFederationCreationBlockHeight();

        saveLastRetiredFederationP2SHScript();
    }

    private void saveNewFederation() {
        if (newFederation == null) {
            return;
        }

        saveFederationFormatVersion(NEW_FEDERATION_FORMAT_VERSION.getKey(), newFederation.getFormatVersion());
        bridgeStorageAccessor.saveToRepository(NEW_FEDERATION_KEY.getKey(), newFederation, BridgeSerializationUtils::serializeFederation);
    }

    private void saveOldFederation() {
        if (!shouldSaveOldFederation) {
            return;
        }

        int oldFederationFormatVersion = getOldFederationFormatVersion();
        saveFederationFormatVersion(OLD_FEDERATION_FORMAT_VERSION.getKey(), oldFederationFormatVersion);
        bridgeStorageAccessor.saveToRepository(OLD_FEDERATION_KEY.getKey(), oldFederation, BridgeSerializationUtils::serializeFederation);
    }

    private int getOldFederationFormatVersion() {
        if (oldFederation == null) {
            // assume it is a standard federation to keep backwards compatibility
            return STANDARD_MULTISIG_FEDERATION.getFormatVersion();
        }

        return oldFederation.getFormatVersion();
    }

    private void savePendingFederation() {
        if (!shouldSavePendingFederation) {
            return;
        }

        byte[] serializedPendingFederation = serializePendingFederation();

        // we only need to save the standard part of the fed since the emergency part is constant
        saveFederationFormatVersion(PENDING_FEDERATION_FORMAT_VERSION.getKey(), STANDARD_MULTISIG_FEDERATION.getFormatVersion());

        bridgeStorageAccessor.saveToRepository(PENDING_FEDERATION_KEY.getKey(), serializedPendingFederation);
    }

    private void saveProposedFederation() {
        if (!isProposedFederationSet) {
            return;
        }

        // format version is always non-null in a non-null federation
        Integer formatVersion = Optional.ofNullable(proposedFederation)
            .map(Federation::getFormatVersion)
            .orElse(null);

        saveFederationFormatVersion(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), formatVersion);
        bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), proposedFederation, BridgeSerializationUtils::serializeFederation);
    }

    @Nullable
    private byte[] serializePendingFederation() {
        if (pendingFederation == null) {
            return null;
        }

        return pendingFederation.serialize();
    }

    private void saveFederationFormatVersion(Bytes32 versionKey, Integer version) {
        bridgeStorageAccessor.saveToRepository(versionKey, version, BridgeSerializationUtils::serializeInteger);
        storageVersionEntries.put(versionKey, Optional.ofNullable(version));
    }

    private void saveFederationElection() {
        if (federationElection == null) {
            return;
        }

        bridgeStorageAccessor.saveToRepository(FEDERATION_ELECTION_KEY.getKey(), federationElection, BridgeSerializationUtils::serializeElection);
    }

    private void saveActiveFederationCreationBlockHeight() {
        if (activeFederationCreationBlockHeight == null) {
            return;
        }

        bridgeStorageAccessor.saveToRepository(ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), activeFederationCreationBlockHeight, BridgeSerializationUtils::serializeLong);
    }

    private void saveNextFederationCreationBlockHeight() {
        if (nextFederationCreationBlockHeight == null) {
            return;
        }

        if (nextFederationCreationBlockHeight == -1L) {
            bridgeStorageAccessor.saveToRepository(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), null, BridgeSerializationUtils::serializeLong);
            return;
        }

        bridgeStorageAccessor.saveToRepository(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), nextFederationCreationBlockHeight, BridgeSerializationUtils::serializeLong);
    }

    private void saveLastRetiredFederationP2SHScript() {
        if (lastRetiredFederationP2SHScript == null) {
            return;
        }

        bridgeStorageAccessor.saveToRepository(LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY.getKey(), lastRetiredFederationP2SHScript, BridgeSerializationUtils::serializeScript);
    }
}
