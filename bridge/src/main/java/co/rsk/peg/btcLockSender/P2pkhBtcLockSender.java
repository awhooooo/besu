package co.rsk.peg.btcLockSender;

import co.rsk.peg.utils.PublicKeys;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.script.Script;

public class P2pkhBtcLockSender implements BtcLockSender {

    private TxSenderAddressType txSenderAddressType;
    private Address btcAddress;
    private org.hyperledger.besu.datatypes.Address rskAddress;

    public P2pkhBtcLockSender() {
        this.txSenderAddressType = TxSenderAddressType.P2PKH;
    }

    @Override
    public TxSenderAddressType getTxSenderAddressType() {
        return txSenderAddressType;
    }

    @Override
    public Address getBTCAddress() {
        return this.btcAddress;
    }

    @Override
    public org.hyperledger.besu.datatypes.Address getRskAddress() {
        return this.rskAddress;
    }

    @Override
    public boolean tryParse (BtcTransaction btcTx) {
        if (btcTx == null) {
            return false;
        }
        if (btcTx.getInputs().size() == 0) {
            return false;
        }
        if (btcTx.getInput(0).getScriptBytes() == null) {
            return false;
        }

        Script scriptSig = btcTx.getInput(0).getScriptSig();
        if (scriptSig.getChunks().size() != 2) {
            return false;
        }

        try {
            byte[] data = scriptSig.getChunks().get(1).data;

            //Looking for btcAddress
            BtcECKey senderBtcKey = BtcECKey.fromPublicOnly(data);
            this.btcAddress = new Address(btcTx.getParams(), senderBtcKey.getPubKeyHash());

            //Looking for rskAddress
            this.rskAddress = PublicKeys.addressOf(data);
        } catch(Exception e) {
            return false;
        }
        return true;
    }
}
