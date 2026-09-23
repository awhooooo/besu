package co.rsk.peg.storage;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Deterministic UTXOs for the storage tests: federation-style P2SH outputs, 76 bytes each in stream form. */
final class UtxoTestSupport {

    static final NetworkParameters PARAMS = NetworkParameters.fromID(NetworkParameters.ID_REGTEST);
    static final Script P2SH_SCRIPT = ScriptBuilder.createP2SHOutputScript(2, List.of(key(101), key(102), key(103)));
    static final Script P2PKH_SCRIPT = ScriptBuilder.createOutputScript(key(104).toAddress(PARAMS));

    private UtxoTestSupport() {
    }

    static BtcECKey key(long privateKey) {
        return BtcECKey.fromPrivate(BigInteger.valueOf(privateKey));
    }

    static UTXO utxo(int seed) {
        return utxo(seed, P2SH_SCRIPT);
    }

    static UTXO utxo(int seed, Script script) {
        return new UTXO(Sha256Hash.of(new byte[] {(byte) seed, (byte) (seed >> 8)}), seed % 4, Coin.valueOf(10_000L * (seed + 1)), 700_000 + seed, seed % 5 == 0, script);
    }

    static List<UTXO> utxos(int from, int toExclusive) {
        List<UTXO> out = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) {
            out.add(utxo(i));
        }
        return out;
    }
}
