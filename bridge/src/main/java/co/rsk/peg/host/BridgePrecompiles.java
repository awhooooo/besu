package co.rsk.peg.host;

import co.rsk.peg.BridgeAddresses;
import co.rsk.peg.BridgeSupportFactory;
import co.rsk.peg.RepositoryBtcBlockStoreWithCache;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.constants.BridgeTestNetConstants;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

import java.util.Locale;
import java.util.Map;

/**
 * Builds the bridge as a precompile for a named Bitcoin network.
 *
 * <p>This is the whole of what the node needs to know about the bridge. Everything on the other side
 * of it is Bitcoin: which federation signs, which keys may change it, and which chain's headers and
 * proof of work the bridge will accept. None of that belongs on a Besu class path, so the node names
 * a network and gets back a contract and the address it answers on.
 */
public final class BridgePrecompiles {

    private BridgePrecompiles() {
    }

    /**
     * The bridge for one Bitcoin network, ready to register.
     *
     * <p>The result is meant to be built once and shared by every protocol spec of a chain. The
     * contract holds the Bitcoin header store's cache of recently stored headers, and a second
     * instance would mean a second cache, cold and needlessly filled from storage.
     *
     * @param network mainnet, testnet or regtest, in any case
     * @return the bridge contract, under the address it answers on
     * @throws IllegalArgumentException if no such Bitcoin network exists
     */
    public static Map<Address, PrecompiledContract> forNetwork(String network) {
        BridgeConstants bridgeConstants = constantsFor(network);
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(
            new RepositoryBtcBlockStoreWithCache.Factory(bridgeConstants.getBtcParams()),
            bridgeConstants
        );

        return Map.of(
            BridgeAddresses.BRIDGE,
            new BridgePrecompiledContract(bridgeConstants, bridgeSupportFactory)
        );
    }

    private static BridgeConstants constantsFor(String network) {
        return switch (network.toLowerCase(Locale.ROOT)) {
            case "mainnet" -> BridgeMainNetConstants.getInstance();
            case "testnet" -> BridgeTestNetConstants.getInstance();
            case "regtest" -> new BridgeRegTestConstants();
            default -> throw new IllegalArgumentException(
                "There is no Bitcoin network called '" + network + "': expected mainnet, testnet or regtest");
        };
    }
}
