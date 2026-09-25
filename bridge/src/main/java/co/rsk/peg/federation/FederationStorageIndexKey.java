package co.rsk.peg.federation;

import co.rsk.peg.utils.StorageKeys;

import org.apache.tuweni.bytes.Bytes32;

public enum FederationStorageIndexKey {

    NEW_FEDERATION_BTC_UTXOS_KEY("newFederationBtcUTXOs"),
    OLD_FEDERATION_BTC_UTXOS_KEY("oldFederationBtcUTXOs"),

    NEW_FEDERATION_KEY("newFederation"),
    OLD_FEDERATION_KEY("oldFederation"),
    PENDING_FEDERATION_KEY("pendingFederation"),
    PROPOSED_FEDERATION("proposedFederation"),

    FEDERATION_ELECTION_KEY("federationElection"),

    ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY("activeFedCreationBlockHeight"),
    NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY("nextFedCreationBlockHeight"),

    LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY("lastRetiredFedP2SHScript"),

    // Format version keys
    NEW_FEDERATION_FORMAT_VERSION("newFederationFormatVersion"),
    OLD_FEDERATION_FORMAT_VERSION("oldFederationFormatVersion"),
    PENDING_FEDERATION_FORMAT_VERSION("pendingFederationFormatVersion"),
    PROPOSED_FEDERATION_FORMAT_VERSION("proposedFederationFormatVersion"),
    ;

    private final String key;

    FederationStorageIndexKey(String key) {
        this.key = key;
    }

    public Bytes32 getKey() {
        return StorageKeys.name(key);
    }
}
