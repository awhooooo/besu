package co.rsk.peg;

import co.rsk.peg.utils.StorageKeys;

import org.apache.tuweni.bytes.Bytes32;

public enum BridgeStorageIndexKey {

    RELEASES_OUTPOINTS_VALUES("releasesOutpointsValues"),
    PEGOUTS_WAITING_FOR_SIGNATURES("rskTxsWaitingFS"),
    RELEASE_REQUEST_QUEUE_WITH_TXHASH("releaseRequestQueueWithTxHash"),
    PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY("releaseTransactionSetWithTxHash"),
    RECEIVE_HEADERS_TIMESTAMP("receiveHeadersLastTimestamp"),
    // Version keys and versions
    NEXT_PEGOUT_HEIGHT_KEY("nextPegoutHeight"),

    // Compound keys
    BTC_TX_HASH_AP("btcTxHashAP"),
    COINBASE_INFORMATION("coinbaseInformation"),
    BTC_BLOCK_HEIGHT("btcBlockHeight"),

    PEGOUT_TX_SIG_HASH("pegoutTxSigHash"),

    SVP_FUND_TX_HASH_UNSIGNED("svpFundTxHashUnsigned"),
    SVP_FUND_TX_SIGNED("svpFundTxSigned"),
    SVP_SPEND_TX_HASH_UNSIGNED("svpSpendTxHashUnsigned"),
    SVP_SPEND_TX_WAITING_FOR_SIGNATURES("svpSpendTxWaitingForSignatures"),

    /**
     * The lowest creation block among the pegouts awaiting confirmation, plus one so that zero means absent.
     * It is a summary of the list under {@link #PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY}, held in a
     * single slot so that the common call can tell that nothing is confirmed yet without loading the list.
     */
    PEGOUTS_WAITING_FOR_CONFIRMATIONS_EARLIEST("pegoutsWaitingEarliest"),
    ;

    private final String key;

    BridgeStorageIndexKey(String key) {
        this.key = key;
    }

    public Bytes32 getKey() {
        return StorageKeys.name(key);
    }

    public Bytes32 getCompoundKey(String delimiter, String identifier) {
        return StorageKeys.compound(key + delimiter + identifier);
    }
}
