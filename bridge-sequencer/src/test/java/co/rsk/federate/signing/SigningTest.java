package co.rsk.federate.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.federate.signing.keyfile.KeyFileChecker;
import co.rsk.federate.signing.keyfile.KeyFileHandler;
import co.rsk.peg.utils.PublicKeys;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One key to a signer, and a signer only ever answers for the key it holds. */
class SigningTest {

    private static final String BTC_KEY =
        "45c5b07fc1a6f58892615b7c31dca6c96db58c4bbc538a6b8a22999aaa860c32";
    private static final String RSK_KEY =
        "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";
    private static final Bytes32 DIGEST =
        Bytes32.fromHexString("0x1122334455667788990011223344556677889900112233445566778899001122");

    @TempDir Path keys;

    @Test
    void aSignerAnswersOnlyForItsOwnKey() throws Exception {
        ECDSASigner btc = new ECDSASignerFromFileKey(new KeyId("BTC"), keyFile("btc", BTC_KEY).toString());

        assertTrue(btc.canSignWith(new KeyId("BTC")));
        assertFalse(btc.canSignWith(new KeyId("RSK")));
        assertThrows(SignerException.class, () -> btc.sign(new KeyId("RSK"), DIGEST));
        assertThrows(SignerException.class, () -> btc.getPublicKey(new KeyId("RSK")));
    }

    @Test
    void aSignatureVerifiesAgainstThePublicKeyTheSignerReports() throws Exception {
        ECDSASigner btc = new ECDSASignerFromFileKey(new KeyId("BTC"), keyFile("btc", BTC_KEY).toString());

        BtcECKey.ECDSASignature signature = btc.sign(new KeyId("BTC"), DIGEST);

        assertTrue(
            btc.getPublicKey(new KeyId("BTC"))
                .toBtcKey()
                .verify(Sha256Hash.wrap(DIGEST.toArrayUnsafe()), signature));
    }

    @Test
    void theCompositeRoutesEachKeyToItsOwnSigner() throws Exception {
        ECDSACompositeSigner composite =
            new ECDSACompositeSigner()
                .addSigner(new ECDSASignerFromFileKey(new KeyId("BTC"), keyFile("btc", BTC_KEY).toString()))
                .addSigner(new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile("rsk", RSK_KEY).toString()));

        assertEquals(expectedKey(BTC_KEY), composite.getPublicKey(new KeyId("BTC")));
        assertEquals(expectedKey(RSK_KEY), composite.getPublicKey(new KeyId("RSK")));
        // MST is a real key id that nothing here holds: it must fail rather than fall through to another.
        assertThrows(SignerException.class, () -> composite.getPublicKey(new KeyId("MST")));
        assertFalse(composite.canSignWith(new KeyId("MST")));
    }

    @Test
    void theCompositeNeverLetsOneKeySignForAnother() throws Exception {
        ECDSACompositeSigner composite =
            new ECDSACompositeSigner()
                .addSigner(new ECDSASignerFromFileKey(new KeyId("BTC"), keyFile("btc", BTC_KEY).toString()))
                .addSigner(new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile("rsk", RSK_KEY).toString()));

        BtcECKey.ECDSASignature asBtc = composite.sign(new KeyId("BTC"), DIGEST);
        BtcECKey.ECDSASignature asRsk = composite.sign(new KeyId("RSK"), DIGEST);

        assertFalse(asBtc.equals(asRsk), "two different keys must not produce the same signature");
        assertTrue(expectedKey(BTC_KEY).toBtcKey().verify(Sha256Hash.wrap(DIGEST.toArrayUnsafe()), asBtc));
        assertFalse(expectedKey(RSK_KEY).toBtcKey().verify(Sha256Hash.wrap(DIGEST.toArrayUnsafe()), asBtc));
    }

    @Test
    void aKeyFileReadableByAnyoneElseIsRefused() throws Exception {
        Path loose = keys.resolve("loose.key");
        Files.writeString(loose, BTC_KEY);
        Files.setPosixFilePermissions(
            loose, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));

        assertTrue(
            new KeyFileChecker(loose.toString()).check().stream()
                .anyMatch(message -> message.contains("permissions")),
            "a key others can read must be reported");
    }

    @Test
    void whatIsWrongWithAKeyFileIsSaidBeforeAnythingSignsWithIt() throws Exception {
        assertTrue(
            new KeyFileChecker(keys.resolve("absent.key").toString()).check().stream()
                .anyMatch(message -> message.contains("does not exist")));

        Path tooShort = keys.resolve("short.key");
        Files.writeString(tooShort, "0011");
        Files.setPosixFilePermissions(tooShort, Set.of(PosixFilePermission.OWNER_READ));
        assertTrue(
            new KeyFileChecker(tooShort.toString()).check().stream()
                .anyMatch(message -> message.contains("Invalid key size")));

        assertTrue(new KeyFileChecker(keyFile("ok", BTC_KEY).toString()).check().isEmpty());
    }

    @Test
    void readingAKeyGivesAFreshArrayEveryTime() throws Exception {
        // The handler must not hand out a reference to anything it keeps, or clearing one copy would
        // corrupt another caller's.
        KeyFileHandler handler = new KeyFileHandler(keyFile("btc", BTC_KEY).toString());

        byte[] first = handler.privateKey();
        byte[] second = handler.privateKey();
        java.util.Arrays.fill(first, (byte) 0);

        assertEquals(32, second.length);
        assertFalse(java.util.Arrays.equals(first, second));
    }

    @Test
    void aPublicKeyIsHeldCompressedAndKnowsItsAddress() throws Exception {
        BtcECKey key = BtcECKey.fromPrivate(new BigInteger(BTC_KEY, 16));

        // The same key given uncompressed and compressed must compare equal and give one address.
        ECPublicKey fromUncompressed = new ECPublicKey(PublicKeys.uncompressed(key));
        ECPublicKey fromCompressed = new ECPublicKey(PublicKeys.compressed(key));

        assertEquals(fromUncompressed, fromCompressed);
        assertEquals(33, fromCompressed.getCompressedKeyBytes().length);
        assertEquals(PublicKeys.addressOf(key), fromCompressed.toAddress());
    }

    @Test
    void somethingThatIsNotAPointOnTheCurveIsNotAPublicKey() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ECPublicKey(new byte[] {0x02, 0x00, 0x00, 0x00}));
    }

    private Path keyFile(String name, String hexKey) throws IOException {
        Path path = keys.resolve(name + ".key");
        Files.writeString(path, hexKey);
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ));
        return path;
    }

    private static ECPublicKey expectedKey(String hexPrivateKey) {
        return new ECPublicKey(BtcECKey.fromPrivate(new BigInteger(hexPrivateKey, 16)).getPubKey());
    }
}
