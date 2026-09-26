package co.rsk.federate.bitcoin;

import static co.rsk.federate.testing.BitcoinFixture.REGTEST;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import co.rsk.federate.testing.BitcoinFixture;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.junit.jupiter.api.Test;

/**
 * The height lookup is what decides whether the bridge's chain and this peer's agree, and after a
 * fork the store holds blocks at the same height on both sides. Asking the store by height would
 * sometimes answer with the abandoned one, so the walk back from the head is the whole point.
 */
class BitcoinWrapperImplTest {

    @Test
    void theHeadIsFoundAtItsOwnHeight() throws BlockStoreException {
        MemoryBlockStore store = new MemoryBlockStore(REGTEST);
        List<StoredBlock> chain = chainOf(store, 4);

        assertThat(BitcoinWrapperImpl.blockAtHeight(store, 4)).isEqualTo(chain.get(4));
    }

    @Test
    void anEarlierHeightIsReachedByWalkingBack() throws BlockStoreException {
        MemoryBlockStore store = new MemoryBlockStore(REGTEST);
        List<StoredBlock> chain = chainOf(store, 6);

        for (int height = 0; height <= 6; height++) {
            assertThat(BitcoinWrapperImpl.blockAtHeight(store, height)).isEqualTo(chain.get(height));
        }
    }

    @Test
    void aBlockThatLostAForkIsNotTheBlockAtItsHeight() throws BlockStoreException {
        MemoryBlockStore store = new MemoryBlockStore(REGTEST);
        List<StoredBlock> chain = chainOf(store, 3);

        // A competing block at height 3, stored but never on the best chain.
        Block abandoned = BitcoinFixture.block(
            chain.get(2).getHeader().getHash(), List.of(BitcoinFixture.coinbase(99)));
        store.put(new StoredBlock(abandoned.cloneAsHeader(), abandoned.getWork(), 3));

        StoredBlock found = BitcoinWrapperImpl.blockAtHeight(store, 3);
        assertThat(found).isEqualTo(chain.get(3));
        assertThat(found.getHeader().getHash()).isNotEqualTo(abandoned.getHash());
    }

    @Test
    void aHeightAboveTheHeadHasNoBlock() throws BlockStoreException {
        MemoryBlockStore store = new MemoryBlockStore(REGTEST);
        chainOf(store, 2);

        assertThat(BitcoinWrapperImpl.blockAtHeight(store, 3)).isNull();
        assertThat(BitcoinWrapperImpl.blockAtHeight(store, 500)).isNull();
    }

    @Test
    void aNegativeHeightHasNoBlock() throws BlockStoreException {
        MemoryBlockStore store = new MemoryBlockStore(REGTEST);
        chainOf(store, 2);

        assertThat(BitcoinWrapperImpl.blockAtHeight(store, -1)).isNull();
    }

    @Test
    void aStoreThatStopsShortAnswersNothingRatherThanTheWrongBlock() throws BlockStoreException {
        // A store pruned below the height being asked for: the walk runs out of blocks.
        MemoryBlockStore store = new MemoryBlockStore(REGTEST) {
            @Override
            public StoredBlock get(Sha256Hash hash) throws BlockStoreException {
                StoredBlock block = super.get(hash);
                return block != null && block.getHeight() < 2 ? null : block;
            }
        };
        chainOf(store, 4);

        assertThat(BitcoinWrapperImpl.blockAtHeight(store, 0)).isNull();
        assertThat(BitcoinWrapperImpl.blockAtHeight(store, 3)).isNotNull();
    }

    /** A best chain of {@code height} blocks above the store's genesis, with the head set. */
    private static List<StoredBlock> chainOf(BlockStore store, int height) throws BlockStoreException {
        List<StoredBlock> chain = new java.util.ArrayList<>();
        StoredBlock cursor = store.getChainHead();
        chain.add(cursor);
        for (int i = 1; i <= height; i++) {
            Block block = BitcoinFixture.block(
                cursor.getHeader().getHash(), List.of(BitcoinFixture.coinbase(i)));
            cursor = new StoredBlock(block.cloneAsHeader(), block.getWork(), i);
            store.put(cursor);
            chain.add(cursor);
        }
        store.setChainHead(cursor);
        return chain;
    }
}
