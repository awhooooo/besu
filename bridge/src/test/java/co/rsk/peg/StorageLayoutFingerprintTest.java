package co.rsk.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.rsk.bitcoinj.core.BtcBlock;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.StoredBlock;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.bitcoinj.script.Script;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.bitcoin.CoinbaseInformation;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationArgs;
import co.rsk.peg.federation.FederationChangeCaller;
import co.rsk.peg.federation.FederationFactory;
import co.rsk.peg.federation.FederationStorageProviderImpl;
import co.rsk.peg.federation.FederationTestUtils;
import co.rsk.peg.federation.P2shErpFederationBuilder;
import co.rsk.peg.federation.PendingFederationBuilder;
import co.rsk.peg.federation.StandardMultiSigFederationBuilder;
import co.rsk.peg.feeperkb.FeePerKbStorageProviderImpl;
import co.rsk.peg.host.FrameBridgeHost;
import co.rsk.peg.lockingcap.LockingCapStorageProviderImpl;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.utils.PublicKeys;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import org.bouncycastle.util.encoders.Hex;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.core.MessageFrameTestFixture;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Pins the physical storage layout of the bridge account. Every storage provider and the block store write a fixed
 * state through a real {@link FrameBridgeHost} into a world state, and the slots they leave are hashed into one
 * digest. The digest moves whenever a key derivation, a serializer, the chunked-storage layout or the UTXO record
 * layout changes. All of those are consensus visible, so a change here has to be deliberate and come with a
 * migration of the state already on the chain; update the two constants only for such a change.
 */
class StorageLayoutFingerprintTest {

    private static final int EXPECTED_SLOTS = 224;
    private static final String EXPECTED_DIGEST = "0xea23fca93c895dbc4e1c157637cfea9c1deee63d06ea70d2762923dc2c02d109";

    private static final BridgeConstants constants = BridgeMainNetConstants.getInstance();
    private static final NetworkParameters params = constants.getBtcParams();

    // Mainnet block 849137
    private static final String BLOCK_HEADER = "00e00820925b77c9ff4d0036aa29f3238cde12e9af9d55c34ed30200000000000000000032a9fa3e12ef87a2327b55db6a16a1227bb381db8b269d90aa3a6e38cf39665f91b47766255d0317c1b1575f";
    private static final int BLOCK_HEIGHT = 849137;

    @Test
    void theProvidersLeaveTheRecordedSlots() {
        MutableWorldState world = InMemoryKeyValueStorageProvider.createInMemoryWorldState();
        MessageFrame frame = new MessageFrameTestFixture()
            .worldUpdater(world.updater())
            .address(BridgeAddresses.BRIDGE)
            .contract(BridgeAddresses.BRIDGE)
            .blockHeader(new BlockHeaderTestFixture().number(1).buildHeader())
            .build();
        FrameBridgeHost host = new FrameBridgeHost(frame);
        StorageAccessor storage = new BridgeStorageAccessorImpl(host);

        BridgeStorageProvider bridgeStorageProvider = new BridgeStorageProvider(storage, params);
        writeBlockStore(host, bridgeStorageProvider);
        writeBridgeState(bridgeStorageProvider);
        writeFederationState(storage);
        writeFeePerKbState(storage);
        writeLockingCapState(storage);

        SortedMap<UInt256, UInt256> slots = new TreeMap<>();
        frame.getWorldUpdater().getAccount(BridgeAddresses.BRIDGE).getUpdatedStorage().forEach((slot, value) -> {
            if (!value.isZero()) {
                slots.put(slot, value);
            }
        });

        List<Bytes> entries = new ArrayList<>();
        slots.forEach((slot, value) -> entries.add(Bytes.concatenate(slot.toBytes(), value.toBytes())));
        String digest = Hash.hash(Bytes.concatenate(entries.toArray(new Bytes[0]))).toHexString();

        assertEquals(EXPECTED_SLOTS, slots.size(), "slots written; the digest over them is " + digest);
        assertEquals(EXPECTED_DIGEST, digest, "digest over the " + slots.size() + " slots written");
    }

    private static void writeBlockStore(FrameBridgeHost host, BridgeStorageProvider bridgeStorageProvider) {
        // The constructor stores the genesis block and makes it the chain head
        RepositoryBtcBlockStoreWithCache blockStore = new RepositoryBtcBlockStoreWithCache(
            params, host, new HashMap<>(), constants, bridgeStorageProvider);
        BtcBlock header = new BtcBlock(params, Hex.decode(BLOCK_HEADER));
        StoredBlock block = new StoredBlock(header, BigInteger.valueOf(1_000_000L), BLOCK_HEIGHT);
        blockStore.put(block);
        blockStore.setChainHead(block);
    }

    private static void writeBridgeState(BridgeStorageProvider provider) {
        provider.setHeightBtcTxhashAlreadyProcessed(BitcoinTestUtils.createHash(1), 100L);
        provider.setHeightBtcTxhashAlreadyProcessed(BitcoinTestUtils.createHash(2), 200L);
        provider.getReleaseRequestQueue().add(BitcoinTestUtils.createP2PKHAddress(params, "requester"), Coin.COIN, PegTestUtils.createHash3(1));
        provider.getPegoutsWaitingForConfirmations().add(transaction(21), 10L, PegTestUtils.createHash3(2));
        provider.getPegoutsWaitingForSignatures().put(PegTestUtils.createHash3(3), transaction(22));
        provider.setCoinbaseInformation(BitcoinTestUtils.createHash(4), new CoinbaseInformation(BitcoinTestUtils.createHash(5)));
        provider.setReceiveHeadersLastTimestamp(1_700_000_000_000L);
        provider.setNextPegoutHeight(123L);
        provider.setPegoutTxSigHash(BitcoinTestUtils.createHash(7));
        provider.setReleaseOutpointsValues(BitcoinTestUtils.createHash(8), List.of(Coin.COIN, Coin.SATOSHI));
        provider.setSvpFundTxHashUnsigned(BitcoinTestUtils.createHash(9));
        provider.setSvpFundTxSigned(transaction(23));
        provider.setSvpSpendTxHashUnsigned(BitcoinTestUtils.createHash(10));
        provider.setSvpSpendTxWaitingForSignatures(new AbstractMap.SimpleEntry<>(PegTestUtils.createHash3(4), transaction(24)));
        provider.save();
    }

    private static void writeFederationState(StorageAccessor storage) {
        FederationStorageProviderImpl provider = new FederationStorageProviderImpl(storage);
        Federation newFederation = P2shErpFederationBuilder.builder().build();
        Federation oldFederation = StandardMultiSigFederationBuilder.builder().build();
        provider.setNewFederation(newFederation);
        provider.setOldFederation(oldFederation);
        provider.setPendingFederation(PendingFederationBuilder.builder().build());
        provider.setProposedFederation(proposedFederation());

        provider.getNewFederationBtcUTXOs().addAll(utxos(31, 3, newFederation.getP2SHScript()));
        List<UTXO> oldFederationUtxos = provider.getOldFederationBtcUTXOs();
        oldFederationUtxos.addAll(utxos(41, 2, oldFederation.getP2SHScript()));
        // Leaves a tombstone: one dead record, no compaction
        oldFederationUtxos.remove(0);

        provider.getFederationElection(constants.getFederationConstants().getFederationChangeAuthorizer())
            .vote(new ABICallSpec("create", new byte[][]{}), FederationChangeCaller.FIRST_AUTHORIZED.getAddress());
        provider.setActiveFederationCreationBlockHeight(50L);
        provider.setNextFederationCreationBlockHeight(60L);
        provider.setLastRetiredFederationP2SHScript(oldFederation.getP2SHScript());
        provider.save();
    }

    private static void writeFeePerKbState(StorageAccessor storage) {
        FeePerKbStorageProviderImpl provider = new FeePerKbStorageProviderImpl(storage);
        provider.setFeePerKb(Coin.valueOf(5_000L));
        BtcECKey voter = BtcECKey.fromPrivate(BigInteger.valueOf(101));
        AddressBasedAuthorizer authorizer = new AddressBasedAuthorizer(List.of(voter), AddressBasedAuthorizer.MinimumRequiredCalculation.ONE);
        provider.getFeePerKbElection(authorizer).vote(new ABICallSpec("setFeePerKb", new byte[][]{{1}}), PublicKeys.addressOf(voter));
        provider.save();
    }

    private static void writeLockingCapState(StorageAccessor storage) {
        LockingCapStorageProviderImpl provider = new LockingCapStorageProviderImpl(storage);
        provider.setLockingCap(Coin.valueOf(21_000_000L));
        provider.save();
    }

    /** A P2SH ERP federation whose members' RSK and MST keys are fixed, unlike FederationTestUtils' random ones. */
    private static Federation proposedFederation() {
        List<BtcECKey> members = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{"fa01", "fa02", "fa03", "fa04", "fa05", "fa06", "fa07", "fa08", "fa09"}, true);
        List<BtcECKey> erpKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(new String[]{"fb01", "fb02", "fb03", "fb04"}, true);
        FederationArgs args = new FederationArgs(FederationTestUtils.getFederationMembersWithKeys(members), Instant.ofEpochMilli(0), 0, params);
        return FederationFactory.buildP2shErpFederation(args, erpKeys, 52_560);
    }

    private static BtcTransaction transaction(int seed) {
        BtcTransaction tx = new BtcTransaction(params);
        tx.addInput(BitcoinTestUtils.createHash(seed), 0, new Script(new byte[0]));
        tx.addOutput(Coin.COIN, BitcoinTestUtils.createP2PKHAddress(params, "output" + seed));
        return tx;
    }

    private static List<UTXO> utxos(int firstSeed, int count, Script script) {
        List<UTXO> utxos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int seed = firstSeed + i;
            utxos.add(new UTXO(BitcoinTestUtils.createHash(seed), i, Coin.valueOf(10_000L * seed), 800_000 + i, false, script));
        }
        return utxos;
    }
}
