package co.rsk.federate.tx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.federate.signing.ECDSASigner;
import co.rsk.federate.signing.ECDSASignerFromFileKey;
import co.rsk.federate.signing.KeyId;
import co.rsk.federate.signing.SignerException;
import co.rsk.peg.utils.PublicKeys;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What this module signs has to be what a node would accept, and the only judge of that worth
 * trusting is the node's own decoder. So every case here hands the bytes to Besu's
 * {@code Transaction.readFrom} and asks it who sent them: if the signing preimage were wrong by a
 * single field the encoding would still be valid and the recovered sender would simply be somebody
 * else, which no amount of reading the code would catch.
 */
class LegacyTransactionSignerTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
    private static final Address BRIDGE =
        Address.fromHexString("0x0000000000000000000000000000000001000006");
    private static final String PRIVATE_KEY =
        "45c5b07fc1a6f58892615b7c31dca6c96db58c4bbc538a6b8a22999aaa860c32";

    @TempDir Path keyDirectory;

    private ECDSASigner signer;
    private KeyId rsk;
    private Address expectedSender;

    @BeforeEach
    void setUp() throws IOException {
        Path keyFile = keyDirectory.resolve("rsk.key");
        Files.writeString(keyFile, PRIVATE_KEY);
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));

        rsk = new KeyId("RSK");
        signer = new ECDSASignerFromFileKey(rsk, keyFile.toString());
        expectedSender =
            PublicKeys.addressOf(BtcECKey.fromPrivate(new BigInteger(PRIVATE_KEY, 16)));
    }

    @Test
    void besuRecoversTheSenderFromWhatWeSign() throws SignerException {
        LegacyTransactionSigner subject = new LegacyTransactionSigner(signer, rsk, CHAIN_ID);
        LegacyTransactionSigner.UnsignedTransaction unsigned = updateCollections(7, BigInteger.valueOf(1_000));

        Transaction decoded = Transaction.readFrom(subject.sign(unsigned));

        assertEquals(expectedSender, decoded.getSender());
        assertEquals(7, decoded.getNonce());
        assertEquals(Wei.of(1_000), decoded.getGasPrice().orElseThrow());
        assertEquals(300_000L, decoded.getGasLimit());
        assertEquals(BRIDGE, decoded.getTo().orElseThrow());
        assertEquals(Wei.ZERO, decoded.getValue());
        assertEquals(unsigned.payload(), decoded.getPayload());
        assertEquals(CHAIN_ID, decoded.getChainId().orElseThrow());
    }

    @Test
    void theSignerAndTheSenderAgree() throws SignerException {
        LegacyTransactionSigner subject = new LegacyTransactionSigner(signer, rsk, CHAIN_ID);

        assertEquals(expectedSender, subject.senderAddress());
    }

    @Test
    void aZeroGasPriceTransactionStillRecovers() throws SignerException {
        // What the sequencer sends inside the bootstrap window, where the exemption waives the fee
        // floor but the encoding must still be exactly right.
        LegacyTransactionSigner subject = new LegacyTransactionSigner(signer, rsk, CHAIN_ID);

        Transaction decoded = Transaction.readFrom(subject.sign(updateCollections(0, BigInteger.ZERO)));

        assertEquals(expectedSender, decoded.getSender());
        assertEquals(Wei.ZERO, decoded.getGasPrice().orElseThrow());
        assertEquals(0, decoded.getNonce());
    }

    @Test
    void theChainIsInTheSignature() throws SignerException {
        // A transaction signed for chain 33 must not recover the same sender when read as chain 34's,
        // which is the whole point of replay protection.
        Bytes forThirtyThree =
            new LegacyTransactionSigner(signer, rsk, CHAIN_ID).sign(updateCollections(1, BigInteger.ONE));
        Bytes forThirtyFour =
            new LegacyTransactionSigner(signer, rsk, BigInteger.valueOf(34))
                .sign(updateCollections(1, BigInteger.ONE));

        assertEquals(CHAIN_ID, Transaction.readFrom(forThirtyThree).getChainId().orElseThrow());
        assertEquals(BigInteger.valueOf(34), Transaction.readFrom(forThirtyFour).getChainId().orElseThrow());
        assertTrue(!forThirtyThree.equals(forThirtyFour), "the chain must change the bytes");
    }

    @Test
    void everySignatureIsCanonical() throws SignerException {
        // Both chains reject a high-S signature, and canonicalising after choosing the recovery id
        // would invalidate it, so this checks the order was right for a range of nonces.
        LegacyTransactionSigner subject = new LegacyTransactionSigner(signer, rsk, CHAIN_ID);

        for (long nonce = 0; nonce < 40; nonce++) {
            Transaction decoded = Transaction.readFrom(subject.sign(updateCollections(nonce, BigInteger.TEN)));
            assertEquals(expectedSender, decoded.getSender(), "nonce " + nonce);
            assertTrue(
                new BtcECKey.ECDSASignature(
                        decoded.getSignature().getR(), decoded.getSignature().getS())
                    .isCanonical(),
                "signature for nonce " + nonce + " is not canonical");
        }
    }

    @Test
    void theSigningHashIsNotTheHashOfWhatIsSent() throws SignerException {
        // Stated as a test because it is the mistake this class exists to avoid: EIP-155 signs a list
        // ending in chainId, 0, 0 where the wire form ends in v, r, s.
        LegacyTransactionSigner subject = new LegacyTransactionSigner(signer, rsk, CHAIN_ID);
        LegacyTransactionSigner.UnsignedTransaction unsigned = updateCollections(3, BigInteger.ONE);

        Bytes signed = subject.sign(unsigned);

        assertTrue(
            !subject.signingHash(unsigned).equals(org.hyperledger.besu.crypto.Hash.keccak256(signed)),
            "the signing hash must not be the hash of the signed transaction");
    }

    @Test
    void aSignerWithoutThatKeyRefuses() {
        assertThrows(
            SignerException.class,
            () -> new LegacyTransactionSigner(signer, new KeyId("BTC"), CHAIN_ID));
    }

    @Test
    void thePayloadSurvivesUnchanged() throws SignerException {
        byte[] payload = new byte[600];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        LegacyTransactionSigner subject = new LegacyTransactionSigner(signer, rsk, CHAIN_ID);

        Transaction decoded =
            Transaction.readFrom(
                subject.sign(
                    new LegacyTransactionSigner.UnsignedTransaction(
                        2, BigInteger.ONE, 900_000, BRIDGE, BigInteger.ZERO, Bytes.wrap(payload))));

        assertArrayEquals(payload, decoded.getPayload().toArrayUnsafe());
        assertEquals(expectedSender, decoded.getSender());
    }

    private static LegacyTransactionSigner.UnsignedTransaction updateCollections(
        final long nonce, final BigInteger gasPrice) {
        return new LegacyTransactionSigner.UnsignedTransaction(
            nonce, gasPrice, 300_000, BRIDGE, BigInteger.ZERO, Bytes.fromHexString("0x0c5a9990"));
    }
}
