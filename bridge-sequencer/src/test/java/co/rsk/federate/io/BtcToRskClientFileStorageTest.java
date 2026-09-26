package co.rsk.federate.io;

import static co.rsk.federate.testing.BitcoinFixture.REGTEST;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import co.rsk.federate.CoinbaseInformation;
import co.rsk.federate.Proof;
import co.rsk.federate.testing.BitcoinFixture;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.PartialMerkleTree;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What this file holds is evidence about blocks that may be far behind by the time it is read, and
 * losing it silently means a peg-in waits for a transaction that will never be broadcast again. So
 * these check the round trip exactly, and that damage is reported rather than swallowed.
 */
class BtcToRskClientFileStorageTest {

    @TempDir Path directory;

    private BtcToRskClientFileStorage storageIn(Path directory) {
        return new BtcToRskClientFileStorageImpl(new BtcToRskClientFileStorageInfo(directory));
    }

    @Test
    void aMissingFileIsAFirstStart() throws IOException {
        BtcToRskClientFileReadResult result = storageIn(directory).read(REGTEST);

        assertThat(result.success()).isTrue();
        assertThat(result.data().getTransactionProofs()).isEmpty();
        assertThat(result.data().getCoinbaseInformationMap()).isEmpty();
    }

    @Test
    void proofsSurviveTheRoundTrip() throws IOException {
        BtcToRskClientFileStorage storage = storageIn(directory);
        Transaction payment = BitcoinFixture.payTo(BitcoinFixture.someP2shAddress(7), Coin.COIN, 1);
        Block block = BitcoinFixture.block(
            REGTEST.getGenesisBlock().getHash(), List.of(BitcoinFixture.coinbase(1), payment));
        PartialMerkleTree pmt = pmtFor(block, payment);

        BtcToRskClientFileData written = new BtcToRskClientFileData();
        written.getTransactionProofs().put(payment.getWTxId(), List.of(new Proof(block.getHash(), pmt)));
        storage.write(written);

        BtcToRskClientFileData read = storage.read(REGTEST).data();
        assertThat(read.getTransactionProofs()).containsOnlyKeys(payment.getWTxId());
        List<Proof> proofs = read.getTransactionProofs().get(payment.getWTxId());
        assertThat(proofs).hasSize(1);
        assertThat(proofs.get(0).getBlockHash()).isEqualTo(block.getHash());
        assertThat(proofs.get(0).getPartialMerkleTree().bitcoinSerialize()).isEqualTo(pmt.bitcoinSerialize());
    }

    @Test
    void aTransactionProvedByTwoBlocksKeepsBothProofs() throws IOException {
        BtcToRskClientFileStorage storage = storageIn(directory);
        Transaction payment = BitcoinFixture.payTo(BitcoinFixture.someP2shAddress(7), Coin.COIN, 1);
        Block first = BitcoinFixture.block(
            REGTEST.getGenesisBlock().getHash(), List.of(BitcoinFixture.coinbase(1), payment));
        Block second = BitcoinFixture.block(
            REGTEST.getGenesisBlock().getHash(), List.of(BitcoinFixture.coinbase(2), payment));

        BtcToRskClientFileData written = new BtcToRskClientFileData();
        written.getTransactionProofs().put(payment.getWTxId(), List.of(
            new Proof(first.getHash(), pmtFor(first, payment)),
            new Proof(second.getHash(), pmtFor(second, payment))));
        storage.write(written);

        List<Proof> proofs = storage.read(REGTEST).data().getTransactionProofs().get(payment.getWTxId());
        assertThat(proofs).extracting(Proof::getBlockHash).containsExactly(first.getHash(), second.getHash());
    }

    @Test
    void aTransactionWithNoProofYetIsStillRemembered() throws IOException {
        // onTransaction records interest before any block has arrived; forgetting that would lose
        // the transaction entirely if the process restarted before its block was seen.
        BtcToRskClientFileStorage storage = storageIn(directory);
        Sha256Hash wtxid = Sha256Hash.of(new byte[] {1, 2, 3});

        BtcToRskClientFileData written = new BtcToRskClientFileData();
        written.getTransactionProofs().put(wtxid, List.of());
        storage.write(written);

        BtcToRskClientFileData read = storage.read(REGTEST).data();
        assertThat(read.getTransactionProofs()).containsOnlyKeys(wtxid);
        assertThat(read.getTransactionProofs().get(wtxid)).isEmpty();
    }

    @Test
    void aCoinbaseSurvivesTheRoundTripIncludingWhetherItWasInformed() throws IOException {
        BtcToRskClientFileStorage storage = storageIn(directory);
        CoinbaseInformation coinbase = segwitCoinbaseInformation();
        coinbase.setReadyToInform(true);

        BtcToRskClientFileData written = new BtcToRskClientFileData();
        written.getCoinbaseInformationMap().put(coinbase.getBlockHash(), coinbase);
        storage.write(written);

        CoinbaseInformation read =
            storage.read(REGTEST).data().getCoinbaseInformationMap().get(coinbase.getBlockHash());
        assertThat(read).isNotNull();
        assertThat(read.getBlockHash()).isEqualTo(coinbase.getBlockHash());
        assertThat(read.getWitnessRoot()).isEqualTo(coinbase.getWitnessRoot());
        assertThat(read.getCoinbaseTransaction().bitcoinSerialize())
            .isEqualTo(coinbase.getCoinbaseTransaction().bitcoinSerialize());
        assertThat(read.getPmt().bitcoinSerialize()).isEqualTo(coinbase.getPmt().bitcoinSerialize());
        // Powpeg dropped this flag on the way to disk, so every restart re-sent every coinbase.
        assertThat(read.isReadyToInform()).isTrue();
    }

    @Test
    void aCoinbaseNotYetInformedComesBackNotInformed() throws IOException {
        BtcToRskClientFileStorage storage = storageIn(directory);
        CoinbaseInformation coinbase = segwitCoinbaseInformation();

        BtcToRskClientFileData written = new BtcToRskClientFileData();
        written.getCoinbaseInformationMap().put(coinbase.getBlockHash(), coinbase);
        storage.write(written);

        assertThat(storage.read(REGTEST).data().getCoinbaseInformationMap()
            .get(coinbase.getBlockHash()).isReadyToInform()).isFalse();
    }

    @Test
    void anEmptyFileIsAFirstStart() throws IOException {
        BtcToRskClientFileStorage storage = storageIn(directory);
        Files.createDirectories(storage.getInfo().getPegDirectory());
        Files.write(storage.getInfo().getFilePath(), new byte[0]);

        assertThat(storage.read(REGTEST).success()).isTrue();
    }

    @Test
    void damageIsReportedRatherThanReadAsEmpty() throws IOException {
        BtcToRskClientFileStorage storage = storageIn(directory);
        Files.createDirectories(storage.getInfo().getPegDirectory());
        Files.write(storage.getInfo().getFilePath(), new byte[] {(byte) 0xc0, 0x01, 0x02, 0x03});

        BtcToRskClientFileReadResult result = storage.read(REGTEST);
        assertThat(result.success()).isFalse();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void aRewriteReplacesTheFileWholeOrNotAtAll() throws IOException {
        BtcToRskClientFileStorage storage = storageIn(directory);
        Sha256Hash first = Sha256Hash.of(new byte[] {1});
        Sha256Hash second = Sha256Hash.of(new byte[] {2});

        BtcToRskClientFileData data = new BtcToRskClientFileData();
        data.getTransactionProofs().put(first, List.of());
        storage.write(data);
        data.getTransactionProofs().put(second, List.of());
        storage.write(data);

        assertThat(storage.read(REGTEST).data().getTransactionProofs()).containsOnlyKeys(first, second);
        // The temporary file is moved over the real one, so it must not be left behind.
        try (var entries = Files.list(storage.getInfo().getPegDirectory())) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                .containsExactly(storage.getInfo().getFilePath().getFileName().toString());
        }
    }

    @Test
    void theDirectoryIsCreatedOnFirstWrite() throws IOException {
        BtcToRskClientFileStorage storage = storageIn(directory.resolve("not").resolve("there"));
        storage.write(new BtcToRskClientFileData());

        assertThat(Files.exists(storage.getInfo().getFilePath())).isTrue();
    }

    private static CoinbaseInformation segwitCoinbaseInformation() {
        Transaction payment = BitcoinFixture.segwitPayTo(BitcoinFixture.someP2shAddress(7), Coin.COIN, 3);
        byte[] reserved = new byte[32];
        Sha256Hash witnessRoot = BitcoinFixture.witnessMerkleRoot(
            List.of(BitcoinFixture.coinbase(1), payment));
        Transaction coinbase = BitcoinFixture.segwitCoinbase(1, witnessRoot, reserved);
        Block block = BitcoinFixture.block(REGTEST.getGenesisBlock().getHash(), List.of(coinbase, payment));
        return new CoinbaseInformation(coinbase, witnessRoot, block.getHash(), pmtFor(block, coinbase));
    }

    /** A proof of one transaction in a block, built over txids, independently of the client. */
    private static PartialMerkleTree pmtFor(Block block, Transaction wanted) {
        List<Transaction> transactions = block.getTransactions();
        byte[] bits = new byte[(int) Math.ceil(transactions.size() / 8.0)];
        List<Sha256Hash> hashes = transactions.stream().map(Transaction::getTxId).toList();
        for (int i = 0; i < transactions.size(); i++) {
            if (hashes.get(i).equals(wanted.getTxId())) {
                Utils.setBitLE(bits, i);
            }
        }
        return PartialMerkleTree.buildFromLeaves(REGTEST, bits, hashes);
    }
}
