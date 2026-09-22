package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.rsk.bitcoinj.core.BtcECKey;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

class PublicKeysTest {

    /** Private key 2: bitcoinj refuses key 1 (the generator scalar), and this address is a standard published vector. */
    private static final BigInteger PRIVATE_KEY = BigInteger.TWO;
    private static final Address ADDRESS_OF_KEY_TWO = Address.fromHexString("0x2B5AD5c4795c026514f8317c7a215E218DcCD6cF");

    @Test
    void addressMatchesEthereumDerivation() {
        BtcECKey key = BtcECKey.fromPrivate(PRIVATE_KEY);
        assertEquals(ADDRESS_OF_KEY_TWO, PublicKeys.addressOf(key));
        assertEquals(ADDRESS_OF_KEY_TWO, PublicKeys.addressOf(PublicKeys.compressed(key)));
        assertEquals(ADDRESS_OF_KEY_TWO, PublicKeys.addressOf(PublicKeys.uncompressed(key)));
    }

    @Test
    void addressMatchesBesuDerivationForRandomKeys() {
        SignatureAlgorithm algorithm = SignatureAlgorithmFactory.getInstance();
        for (long seed = 3; seed < 40; seed++) {
            BigInteger privateKey = BigInteger.valueOf(seed).multiply(BigInteger.valueOf(0x9E3779B97F4A7C15L)).abs().add(BigInteger.TWO);
            KeyPair besuPair = algorithm.createKeyPair(algorithm.createPrivateKey(privateKey));
            Address expected = Address.extract(besuPair.getPublicKey());
            assertEquals(expected, PublicKeys.addressOf(BtcECKey.fromPrivate(privateKey)), "seed " + seed);
        }
    }

    @Test
    void encodingsHaveSecFormLengths() {
        BtcECKey key = BtcECKey.fromPrivate(BigInteger.valueOf(123456789));
        byte[] compressed = PublicKeys.compressed(key);
        byte[] uncompressed = PublicKeys.uncompressed(key);
        assertEquals(33, compressed.length);
        assertEquals(65, uncompressed.length);
        assertEquals(0x04, uncompressed[0]);
        assertEquals(65, PublicKeys.asUncompressedKey(BtcECKey.fromPublicOnly(compressed)).getPubKey().length);
        assertEquals(33, PublicKeys.asCompressedKey(BtcECKey.fromPublicOnly(uncompressed)).getPubKey().length);
        assertArrayEquals(uncompressed, PublicKeys.asUncompressedKey(key).getPubKey());
    }

    @Test
    void invalidKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PublicKeys.addressOf(new byte[] {1, 2, 3}));
    }
}
