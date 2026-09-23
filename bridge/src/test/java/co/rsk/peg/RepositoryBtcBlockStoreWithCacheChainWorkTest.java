package co.rsk.peg;

import static co.rsk.bitcoinj.core.StoredBlock.COMPACT_SERIALIZED_SIZE_LEGACY;
import static co.rsk.bitcoinj.core.StoredBlock.COMPACT_SERIALIZED_SIZE_V2;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.rsk.bitcoinj.core.BtcBlock;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.StoredBlock;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.utils.StorageKeys;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Ported from RSKj's RepositoryBtcBlockStoreWithCacheChainWorkTest. Only the V2 compact format exists on this chain,
 * so the cases for the 12-byte chain work format RSKj wrote before RSKIP454 are gone, and a stored block in that
 * format is now rejected instead of read.
 */
class RepositoryBtcBlockStoreWithCacheChainWorkTest {

    private static final String BLOCK_STORE_CHAIN_HEAD_KEY = "blockStoreChainHead";
    // Max chain work to fit in 12 bytes
    private static final BigInteger MAX_WORK_V1 = new BigInteger(/* 12 bytes */ "ffffffffffffffffffffffff", 16);
    // Chain work too large to fit in 12 bytes
    private static final BigInteger TOO_LARGE_WORK_V1 = new BigInteger(/* 13 bytes */ "ffffffffffffffffffffffffff", 16);
    // Max chain work to fit in 32 bytes
    private static final BigInteger MAX_WORK_V2 = new BigInteger(/* 32 bytes */
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
    // Chain work too large to fit in 32 bytes
    private static final BigInteger TOO_LARGE_WORK_V2 = new BigInteger(/* 33 bytes */
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);

    private static final BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
    private static final NetworkParameters mainnetNetworkParameters = bridgeMainnetConstants.getBtcParams();

    private InMemoryBridgeHost host;
    private RepositoryBtcBlockStoreWithCache repositoryBtcBlockStoreWithCache;

    // Just an arbitrary block
    private static final String BLOCK_HEADER = "00e00820925b77c9ff4d0036aa29f3238cde12e9af9d55c34ed30200000000000000000032a9fa3e12ef87a2327b55db6a16a1227bb381db8b269d90aa3a6e38cf39665f91b47766255d0317c1b1575f";
    private static final int BLOCK_HEIGHT = 849137;
    private static final BtcBlock BLOCK = new BtcBlock(mainnetNetworkParameters, Hex.decode(BLOCK_HEADER));

    @BeforeEach
    void setUp() {
        host = new InMemoryBridgeHost();
        Map<Sha256Hash, StoredBlock> cacheBlocks = new HashMap<>();
        BridgeStorageProvider bridgeStorageProvider = new BridgeStorageProvider(
            new BridgeStorageAccessorImpl(host),
            mainnetNetworkParameters
        );

        repositoryBtcBlockStoreWithCache = new RepositoryBtcBlockStoreWithCache(
            mainnetNetworkParameters,
            host,
            cacheBlocks,
            bridgeMainnetConstants,
            bridgeStorageProvider
        );
    }

    private static Stream<Arguments> validChainWorkForV2() {
        return Stream.of(
            Arguments.of(BigInteger.ZERO), // no work
            Arguments.of(BigInteger.ONE), // small work
            Arguments.of(BigInteger.valueOf(Long.MAX_VALUE)), // a larg-ish work
            Arguments.of(MAX_WORK_V1),
            Arguments.of(TOO_LARGE_WORK_V1),
            Arguments.of(MAX_WORK_V2)
        );
    }

    private static Stream<Arguments> invalidChainWorkForV2() {
        return Stream.of(
            Arguments.of(TOO_LARGE_WORK_V2)
        );
    }

    @ParameterizedTest()
    @MethodSource("validChainWorkForV2")
    void put_whenChainWorkAnySizeUnder32Bytes_shouldStoreBlock(BigInteger chainWork) {
        int expectedCompactSerializedSize = COMPACT_SERIALIZED_SIZE_V2;

        StoredBlock storedBlock = new StoredBlock(BLOCK, chainWork, BLOCK_HEIGHT);

        // act
        repositoryBtcBlockStoreWithCache.put(storedBlock);

        // assert
        Sha256Hash expectedHash = storedBlock.getHeader().getHash();

        ByteBuffer byteBufferForExpectedBlock = ByteBuffer.allocate(expectedCompactSerializedSize);
        storedBlock.serializeCompactV2(byteBufferForExpectedBlock);

        byte[] expectedSerializedBlock = byteBufferForExpectedBlock.array();
        byte[] actualSerializedBlock = host.getStorage(StorageKeys.of(expectedHash));
        assertNotNull(actualSerializedBlock);
        assertEquals(expectedCompactSerializedSize, actualSerializedBlock.length);

        assertArrayEquals(expectedSerializedBlock, actualSerializedBlock);
    }

    @ParameterizedTest()
    @MethodSource("invalidChainWorkForV2")
    void put_whenInvalidChainWorkForV2_shouldFail(BigInteger chainWork) {
        StoredBlock storedBlock = new StoredBlock(BLOCK, chainWork, BLOCK_HEIGHT);

        // act
        IllegalArgumentException actualException = assertThrows(
            IllegalArgumentException.class, () -> repositoryBtcBlockStoreWithCache.put(storedBlock)
        );

        String expectedMessage = "The given number does not fit in 32";
        String actualMessage = actualException.getMessage();
        assertEquals(expectedMessage, actualMessage);
    }

    @ParameterizedTest()
    @MethodSource("validChainWorkForV2")
    void get_whenChainWorkAnySizeUnder32Bytes_shouldGetStoredBlock(BigInteger chainWork) {
        StoredBlock expectedStoreBlock = new StoredBlock(BLOCK, chainWork, BLOCK_HEIGHT);
        Sha256Hash expectedHash = expectedStoreBlock.getHeader().getHash();
        arrangeStorageWithExpectedStoredBlockV2(expectedStoreBlock);

        // act
        StoredBlock actualStoreBlock = repositoryBtcBlockStoreWithCache.get(expectedHash);

        // assert
        assertEquals(expectedStoreBlock, actualStoreBlock);
    }

    @Test
    void get_whenStoredBlockIsInTheLegacyFormat_shouldFail() {
        StoredBlock legacyStoreBlock = new StoredBlock(BLOCK, MAX_WORK_V1, BLOCK_HEIGHT);
        Sha256Hash hash = legacyStoreBlock.getHeader().getHash();
        ByteBuffer byteBuffer = ByteBuffer.allocate(COMPACT_SERIALIZED_SIZE_LEGACY);
        legacyStoreBlock.serializeCompactLegacy(byteBuffer);
        host.putStorage(StorageKeys.of(hash), byteBuffer.array());

        // act & assert
        IllegalStateException actualException = assertThrows(
            IllegalStateException.class, () -> repositoryBtcBlockStoreWithCache.get(hash)
        );
        assertEquals("A stored block must be 116 bytes long, found 96", actualException.getMessage());
    }

    private void arrangeStorageWithExpectedStoredBlockV2(StoredBlock expectedStoreBlock) {
        Sha256Hash expectedHash = expectedStoreBlock.getHeader().getHash();
        ByteBuffer byteBuffer = ByteBuffer.allocate(COMPACT_SERIALIZED_SIZE_V2);
        expectedStoreBlock.serializeCompactV2(byteBuffer);
        host.putStorage(StorageKeys.of(expectedHash), byteBuffer.array());
    }

    @ParameterizedTest()
    @MethodSource("validChainWorkForV2")
    void getChainHead_whenChainWorkAnySizeUnder32Bytes_shouldGetChainHead(BigInteger chainWork) {
        StoredBlock expectedStoreBlock = new StoredBlock(BLOCK, chainWork, BLOCK_HEIGHT);

        arrangeStorageWithChainHeadV2(expectedStoreBlock);

        // act
        StoredBlock actualStoreBlock = repositoryBtcBlockStoreWithCache.getChainHead();

        // assert
        assertEquals(expectedStoreBlock, actualStoreBlock);
    }

    private void arrangeStorageWithChainHeadV2(StoredBlock expectedStoreBlock) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(COMPACT_SERIALIZED_SIZE_V2);
        expectedStoreBlock.serializeCompactV2(byteBuffer);
        host.putStorage(chainHeadKey(), byteBuffer.array());
    }

    @ParameterizedTest()
    @MethodSource("validChainWorkForV2")
    void setChainHead_whenChainWorkAnySizeUnder32Bytes_shouldStoreChainHead(BigInteger chainWork) {
        StoredBlock expectedStoreBlock = new StoredBlock(BLOCK, chainWork, BLOCK_HEIGHT);

        // act
        repositoryBtcBlockStoreWithCache.setChainHead(expectedStoreBlock);

        // assert
        ByteBuffer byteBuffer = ByteBuffer.allocate(COMPACT_SERIALIZED_SIZE_V2);
        expectedStoreBlock.serializeCompactV2(byteBuffer);

        assertArrayEquals(byteBuffer.array(), host.getStorage(chainHeadKey()));
    }

    private static Bytes32 chainHeadKey() {
        return StorageKeys.name(BLOCK_STORE_CHAIN_HEAD_KEY);
    }
}
