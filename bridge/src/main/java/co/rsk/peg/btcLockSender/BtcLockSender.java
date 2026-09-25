package co.rsk.peg.btcLockSender;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcTransaction;

public interface BtcLockSender {

    enum TxSenderAddressType {
        P2PKH,
        P2SHP2WPKH,
        P2SHMULTISIG,
        P2SHP2WSH,
        UNKNOWN
    }

    boolean tryParse(BtcTransaction btcTx);

    TxSenderAddressType getTxSenderAddressType();

    Address getBTCAddress();

    org.hyperledger.besu.datatypes.Address getRskAddress();
}
