package co.rsk.peg.bitcoin;

import co.rsk.bitcoinj.script.Script;
import org.hyperledger.besu.datatypes.Hash;

public interface FlyoverRedeemScriptBuilder {
    Script of(Hash flyoverDerivationHash, Script redeemScript);
}
