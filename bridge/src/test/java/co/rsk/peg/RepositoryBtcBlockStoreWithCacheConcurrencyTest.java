package co.rsk.peg;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import co.rsk.bitcoinj.core.BtcBlock;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.StoredBlock;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The node holds one block store factory, and its header cache is shared by every store instance on every
 * thread: Besu pre-executes the transactions of a block in parallel, and JSON-RPC calls run beside block
 * import. Each execution has its own world state, so the cache is the only thing the stores share.
 */
class RepositoryBtcBlockStoreWithCacheConcurrencyTest {

    private static final BridgeConstants CONSTANTS = new BridgeRegTestConstants();
    private static final NetworkParameters PARAMS = CONSTANTS.getBtcParams();
    private static final int THREADS = 16;
    /** All threads' blocks together stay under the factory's default cache size, so every one of them stays cached. */
    private static final int BLOCKS_PER_THREAD = 500;
    private static final int CACHE_READS_PER_BLOCK = 40;

    /** A header chain built by one thread on its own host, provider and store. */
    private static final class Chain {
        private final InMemoryBridgeHost host = new InMemoryBridgeHost();
        private final BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), PARAMS);
        private final List<BtcBlock> blocks = new ArrayList<>();
        private final List<StoredBlock> stored = new ArrayList<>();
        private BtcBlockStoreWithCache store;

        private BtcBlock atHeight(int height) {
            return blocks.get(height - 1);
        }
    }

    @Test
    void storesOnManyThreadsShareOneCacheSafely() throws Exception {
        assertChainsSurvive(new RepositoryBtcBlockStoreWithCache.Factory(PARAMS), true);
    }

    @Test
    void aSmallSharedCacheEvictsSafelyUnderConcurrentWrites() throws Exception {
        // Sixteen chains through a cache of sixty-four entries: every put evicts while the others read
        assertChainsSurvive(new RepositoryBtcBlockStoreWithCache.Factory(PARAMS, 50, 64), false);
    }

    private static void assertChainsSurvive(RepositoryBtcBlockStoreWithCache.Factory factory, boolean everyBlockStaysCached) throws Exception {
        List<Callable<Chain>> builders = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
            int seed = thread;
            builders.add(() -> buildChain(factory, seed, BLOCKS_PER_THREAD, everyBlockStaysCached));
        }

        List<Chain> chains = ConcurrentRuns.run(builders);

        for (Chain chain : chains) {
            assertEquals(BLOCKS_PER_THREAD, chain.store.getChainHead().getHeight());
            for (int depth = 0; depth < BLOCKS_PER_THREAD; depth++) {
                BtcBlock expected = chain.atHeight(BLOCKS_PER_THREAD - depth);
                assertEquals(expected.getHash(), chain.store.getStoredBlockAtMainChainDepth(depth).getHeader().getHash());
                assertNotNull(chain.store.get(expected.getHash()));
            }
        }
    }

    @Test
    void theCacheOnlyAcceleratesStorage() throws Exception {
        RepositoryBtcBlockStoreWithCache.Factory factory = new RepositoryBtcBlockStoreWithCache.Factory(PARAMS);
        Chain ours = buildChain(factory, 1, 20, true);
        Chain other = buildChain(factory, 2, 20, true);
        // A store over the same state with an empty cache answers from storage alone
        BtcBlockStoreWithCache storageOnly = new RepositoryBtcBlockStoreWithCache(PARAMS, ours.host, new HashMap<>(), CONSTANTS, ours.provider);

        // The other chain's blocks sit in the shared cache, as blocks of a discarded pre-execution would
        Sha256Hash foreign = other.atHeight(20).getHash();
        assertNotNull(ours.store.getFromCache(foreign));
        assertNull(ours.store.get(foreign));

        assertEquals(storageOnly.getChainHead(), ours.store.getChainHead());
        for (int depth = 0; depth < 20; depth++) {
            assertEquals(storageOnly.getStoredBlockAtMainChainDepth(depth), ours.store.getStoredBlockAtMainChainDepth(depth));
        }
        for (BtcBlock block : ours.blocks) {
            assertEquals(storageOnly.get(block.getHash()), ours.store.get(block.getHash()));
        }
    }

    /**
     * Builds the chain block by block, reading through the cache and the depth walk while the other threads write.
     * The cache may answer null for a block it evicted, never a wrong block; under capacity it answers every block.
     */
    private static Chain buildChain(RepositoryBtcBlockStoreWithCache.Factory factory, int seed, int length, boolean everyBlockStaysCached) throws BlockStoreException {
        Chain chain = new Chain();
        chain.store = factory.newInstance(chain.host, CONSTANTS, chain.provider); // stores the genesis block as chain head
        StoredBlock head = chain.store.getChainHead();
        for (int index = 0; index < length; index++) {
            BtcBlock block = header(head.getHeader().getHash(), seed, index);
            head = new StoredBlock(block, head.getChainWork().add(BigInteger.ONE), head.getHeight() + 1);
            chain.store.put(head);
            chain.store.setChainHead(head);
            chain.blocks.add(block);
            chain.stored.add(head);

            for (int recent = Math.max(0, index - CACHE_READS_PER_BLOCK); recent <= index; recent++) {
                StoredBlock cached = chain.store.getFromCache(chain.blocks.get(recent).getHash());
                if (everyBlockStaysCached || cached != null) {
                    assertEquals(chain.stored.get(recent), cached);
                }
            }
            int depth = Math.min(index, 3);
            assertEquals(chain.atHeight(head.getHeight() - depth).getHash(), chain.store.getStoredBlockAtMainChainDepth(depth).getHeader().getHash());
        }
        return chain;
    }

    private static BtcBlock header(Sha256Hash previous, int seed, int index) {
        Sha256Hash merkleRoot = Sha256Hash.of(("chain " + seed + " block " + index).getBytes(UTF_8));
        return new BtcBlock(PARAMS, 1, previous, merkleRoot, index + 1, PARAMS.getGenesisBlock().getDifficultyTarget(), 0, new ArrayList<>());
    }
}
