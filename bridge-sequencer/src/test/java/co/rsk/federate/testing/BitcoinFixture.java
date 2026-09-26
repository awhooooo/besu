package co.rsk.federate.testing;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

/**
 * Real regtest blocks and transactions, built here rather than recorded.
 *
 * <p>The merkle arithmetic is written out again instead of being borrowed from the classes under
 * test: a fixture that computes a root the same way the code does would agree with it however wrong
 * both were.
 */
public final class BitcoinFixture {

    public static final NetworkParameters REGTEST = RegTestParams.get();

    private BitcoinFixture() {
    }

    /**
     * A transaction paying {@code value} to {@code to}, spent from a P2PKH input.
     *
     * <p>The input script is a real signature-and-public-key pair rather than filler, so that the
     * bridge's sender parsing has something to recognise, as it would on the live chain.
     */
    public static Transaction payTo(Address to, Coin value, int salt) {
        Transaction tx = new Transaction(REGTEST);
        byte[] signature = new byte[71];
        Arrays.fill(signature, (byte) salt);
        Script scriptSig = new ScriptBuilder()
            .data(signature)
            .data(senderKey(salt).getPubKey())
            .build();
        tx.addInput(new TransactionInput(
            REGTEST, tx, scriptSig.getProgram(), new TransactionOutPoint(REGTEST, salt, Sha256Hash.ZERO_HASH)));
        tx.addOutput(value, to);
        return tx;
    }

    /** As {@link #payTo}, but with an input script that cannot be parsed at all. */
    public static Transaction payToWithUnparseableInput(Address to, Coin value, int salt) {
        Transaction tx = new Transaction(REGTEST);
        // A push of two bytes with nothing following it.
        tx.addInput(new TransactionInput(
            REGTEST, tx, new byte[] {0x02, 0x01}, new TransactionOutPoint(REGTEST, salt, Sha256Hash.ZERO_HASH)));
        tx.addOutput(value, to);
        return tx;
    }

    /** A deterministic key standing in for whoever sent a peg-in. */
    public static org.bitcoinj.core.ECKey senderKey(int salt) {
        return org.bitcoinj.core.ECKey.fromPrivate(BigInteger.valueOf(1_000L + salt));
    }

    /** As {@link #payTo}, but segwit: the input carries a witness, so the txid and wtxid differ. */
    public static Transaction segwitPayTo(Address to, Coin value, int salt) {
        Transaction tx = payTo(to, value, salt);
        TransactionWitness witness = new TransactionWitness(2);
        witness.setPush(0, new byte[] {(byte) salt, 9, 9});
        witness.setPush(1, new byte[33]);
        tx.getInput(0).setWitness(witness);
        return tx;
    }

    /** A coinbase with no witness, for a block with no segwit transactions in it. */
    public static Transaction coinbase(int height) {
        Transaction tx = new Transaction(REGTEST);
        tx.addInput(new TransactionInput(
            REGTEST, tx, new byte[] {(byte) height, (byte) (height >> 8), 3}));
        // Not a one-byte payload: bitcoinj insists a push be encoded minimally, and a single byte
        // of value 1 would have to be OP_1 rather than a data push.
        tx.addOutput(Coin.FIFTY_COINS, ScriptBuilder.createOpReturnScript(
            new byte[] {(byte) height, (byte) (height >> 8), 'c', 'o', 'i', 'n', 'b', 'a', 's', 'e'}));
        return tx;
    }

    /**
     * A coinbase committing to a witness root, as a miner of a segwit block produces.
     *
     * <p>The commitment is {@code sha256d(witnessRoot || reservedValue)} in an OP_RETURN output
     * tagged {@code aa21a9ed}, and the reserved value is the coinbase's single witness push.
     */
    public static Transaction segwitCoinbase(int height, Sha256Hash witnessRoot, byte[] reservedValue) {
        Transaction tx = coinbase(height);

        byte[] commitment = Sha256Hash.hashTwice(
            concat(witnessRoot.getReversedBytes(), reservedValue));
        byte[] payload = concat(new byte[] {(byte) 0xaa, 0x21, (byte) 0xa9, (byte) 0xed}, commitment);
        tx.addOutput(Coin.ZERO, new Script(ScriptBuilder.createOpReturnScript(payload).getProgram()));

        TransactionWitness witness = new TransactionWitness(1);
        witness.setPush(0, reservedValue);
        tx.getInput(0).setWitness(witness);
        return tx;
    }

    /** A block containing exactly these transactions, the first of which must be the coinbase. */
    public static Block block(Sha256Hash previous, List<Transaction> transactions) {
        Block block = new Block(
            REGTEST,
            Block.BLOCK_VERSION_GENESIS,
            previous,
            Sha256Hash.ZERO_HASH,
            System.currentTimeMillis() / 1000,
            REGTEST.getGenesisBlock().getDifficultyTarget(),
            0,
            new ArrayList<>());
        for (Transaction tx : transactions) {
            block.addTransaction(tx);
        }
        return block;
    }

    /**
     * The witness merkle root of a block: over wtxids, with a zero standing in for the coinbase.
     *
     * <p>This is what a coinbase must commit to, computed independently of the production code.
     */
    public static Sha256Hash witnessMerkleRoot(List<Transaction> transactions) {
        List<Sha256Hash> leaves = new ArrayList<>(transactions.size());
        for (int i = 0; i < transactions.size(); i++) {
            leaves.add(i == 0 ? Sha256Hash.ZERO_HASH : transactions.get(i).getWTxId());
        }
        return merkleRoot(leaves);
    }

    /** The plain merkle root of a list of hashes, pairing and duplicating the odd one out. */
    public static Sha256Hash merkleRoot(List<Sha256Hash> leaves) {
        List<Sha256Hash> level = new ArrayList<>(leaves);
        while (level.size() > 1) {
            List<Sha256Hash> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                Sha256Hash left = level.get(i);
                Sha256Hash right = i + 1 < level.size() ? level.get(i + 1) : left;
                next.add(Sha256Hash.wrapReversed(
                    Sha256Hash.hashTwice(concat(left.getReversedBytes(), right.getReversedBytes()))));
            }
            level = next;
        }
        return level.get(0);
    }

    /** A P2SH address that stands in for a federation's. */
    public static org.bitcoinj.core.LegacyAddress someP2shAddress(int salt) {
        byte[] hash = new byte[20];
        Arrays.fill(hash, (byte) salt);
        return org.bitcoinj.core.LegacyAddress.fromScriptHash(REGTEST, hash);
    }

    public static BigInteger bigint(long value) {
        return BigInteger.valueOf(value);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
