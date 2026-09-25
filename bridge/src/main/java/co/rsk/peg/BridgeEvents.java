package co.rsk.peg;

import co.rsk.peg.abi.AbiFunction;

public enum BridgeEvents {
    PEGIN_BTC("pegin_btc", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.RECEIVER, "address"),
        new AbiFunction.Param(true, Fields.BTC_TX_HASH, "bytes32"),
        new AbiFunction.Param(false, Fields.AMOUNT, "int256"),
        new AbiFunction.Param(false, "protocolVersion", "int256")
    }),
    REJECTED_PEGIN("rejected_pegin", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.BTC_TX_HASH, "bytes32"),
        new AbiFunction.Param(false, Fields.REASON, "int256")
    }),
    UNREFUNDABLE_PEGIN("unrefundable_pegin", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.BTC_TX_HASH, "bytes32"),
        new AbiFunction.Param(false, Fields.REASON, "int256")
    }),
    UPDATE_COLLECTIONS("update_collections", new AbiFunction.Param[] {
        new AbiFunction.Param(false, Fields.SENDER, "address")
    }),
    ADD_SIGNATURE("add_signature", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.RELEASE_RSK_TX_HASH, "bytes32"),
        new AbiFunction.Param(true, "federatorRskAddress", "address"),
        new AbiFunction.Param(false, "federatorBtcPublicKey", "bytes")
    }),
    RELEASE_BTC("release_btc", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.RELEASE_RSK_TX_HASH, "bytes32"),
        new AbiFunction.Param(false, "btcRawTransaction", "bytes")
    }),
    COMMIT_FEDERATION("commit_federation", new AbiFunction.Param[] {
        new AbiFunction.Param(false, "oldFederationBtcPublicKeys", "bytes"),
        new AbiFunction.Param(false, "oldFederationBtcAddress", "string"),
        new AbiFunction.Param(false, "newFederationBtcPublicKeys", "bytes"),
        new AbiFunction.Param(false, "newFederationBtcAddress", "string"),
        new AbiFunction.Param(false, "activationHeight", "int256")
    }),
    COMMIT_FEDERATION_FAILED("commit_federation_failed", new AbiFunction.Param[] {
        new AbiFunction.Param(false, "proposedFederationRedeemScript", "bytes"),
        new AbiFunction.Param(false, "blockNumber", "int256")
    }),
    RELEASE_REQUESTED("release_requested", new AbiFunction.Param[] {
        new AbiFunction.Param(true, "rskTxHash", "bytes32"),
        new AbiFunction.Param(true, Fields.BTC_TX_HASH, "bytes32"),
        new AbiFunction.Param(false, Fields.AMOUNT, "uint256")
    }),
    RELEASE_REQUEST_REJECTED("release_request_rejected", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.SENDER, "address"),
        new AbiFunction.Param(false, Fields.AMOUNT, "uint256"),
        new AbiFunction.Param(false, Fields.REASON, "int256")
    }),
    BATCH_PEGOUT_CREATED("batch_pegout_created", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.BTC_TX_HASH, "bytes32"),
        new AbiFunction.Param(false, Fields.RELEASE_RSK_TX_HASHES, "bytes")
    }),
    RELEASE_REQUEST_RECEIVED("release_request_received", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.SENDER, "address"),
        new AbiFunction.Param(false, Fields.BTC_DESTINATION_ADDRESS, "string"),
        new AbiFunction.Param(false, Fields.AMOUNT, "uint256")
    }),
    PEGOUT_CONFIRMED("pegout_confirmed", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.BTC_TX_HASH, "bytes32"),
        new AbiFunction.Param(false, "pegoutCreationRskBlockNumber", "uint256")
    }),
    PEGOUT_TRANSACTION_CREATED("pegout_transaction_created", new AbiFunction.Param[] {
        new AbiFunction.Param(true, Fields.BTC_TX_HASH, "bytes32"),
        new AbiFunction.Param(false, Fields.UTXO_OUTPOINT_VALUES, "bytes")
    });

    private final String eventName;
    private final AbiFunction.Param[] params;

    BridgeEvents(String eventName, AbiFunction.Param[] params) {
        this.eventName = eventName;
        this.params = params.clone();
    }

    public AbiFunction getEvent() {
        return AbiFunction.fromEventSignature(eventName, params);
    }

    private static class Fields {
        private static final String AMOUNT = "amount";
        private static final String BTC_DESTINATION_ADDRESS = "btcDestinationAddress";
        private static final String BTC_TX_HASH = "btcTxHash";
        private static final String REASON = "reason";
        private static final String RECEIVER = "receiver";
        private static final String RELEASE_RSK_TX_HASH = "releaseRskTxHash";
        private static final String RELEASE_RSK_TX_HASHES = "releaseRskTxHashes";
        private static final String SENDER = "sender";
        private static final String UTXO_OUTPOINT_VALUES = "utxoOutpointValues";
    }
}
