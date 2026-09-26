package co.rsk.peg.host;

import co.rsk.peg.BootstrapWindow;
import co.rsk.peg.BridgeAddresses;
import co.rsk.peg.BridgeSupportFactory;
import co.rsk.peg.RepositoryBtcBlockStoreWithCache;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.constants.BridgeTestNetConstants;
import co.rsk.peg.utils.PublicKeys;
import co.rsk.peg.utils.Weis;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
     * @param bootstrapWindow the blocks during which the peg is still being brought to life
     * @return the bridge contract, under the address it answers on
     * @throws IllegalArgumentException if no such Bitcoin network exists
     */
    public static Map<Address, PrecompiledContract> forNetwork(String network, BootstrapWindow bootstrapWindow) {
        BridgeConstants bridgeConstants = constantsFor(network);
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(
            new RepositoryBtcBlockStoreWithCache.Factory(bridgeConstants.getBtcParams()),
            bridgeConstants,
            bootstrapWindow
        );

        return Map.of(
            BridgeAddresses.BRIDGE,
            new BridgePrecompiledContract(bridgeConstants, bridgeSupportFactory)
        );
    }

    /**
     * The addresses that may send bridge transactions for nothing while the chain bootstraps: the
     * peg's own operators, and nobody else.
     *
     * <p>The genesis federation rather than the current one, which is the same thing while the window
     * is open and is what RSK asked for: once the federation has changed, the chain has plainly
     * outgrown its bootstrap. The authorizers are here because changing the federation, the fee per
     * kilobyte or the locking cap can all be needed before the first peg-in can succeed.
     *
     * @param network mainnet, testnet or regtest, in any case
     * @return the addresses, as the node knows them
     * @throws IllegalArgumentException if no such Bitcoin network exists
     */
    public static Set<Address> bootstrapSendersFor(String network) {
        BridgeConstants bridgeConstants = constantsFor(network);
        Set<Address> senders = new LinkedHashSet<>();

        bridgeConstants.getFederationConstants().getGenesisFederationPublicKeys()
            .forEach(key -> senders.add(PublicKeys.addressOf(key)));
        senders.addAll(bridgeConstants.getFederationConstants().getFederationChangeAuthorizer().getAuthorizedAddresses());
        senders.addAll(bridgeConstants.getFeePerKbConstants().getFeePerKbChangeAuthorizer().getAuthorizedAddresses());
        senders.addAll(bridgeConstants.getLockingCapConstants().getIncreaseAuthorizer().getAuthorizedAddresses());

        return Set.copyOf(senders);
    }

    /**
     * Every coin the chain will ever have, which the bridge must hold at genesis.
     *
     * <p>A coin exists because bitcoin was locked for it. Before any of that has happened the bridge
     * holds all of them and nobody else holds any, and it hands them out only against proof that the
     * bitcoin arrived.
     *
     * @param network mainnet, testnet or regtest, in any case
     * @return the total supply, in wei
     * @throws IllegalArgumentException if no such Bitcoin network exists
     */
    public static Wei totalSupplyFor(String network) {
        return Weis.fromSatoshis(constantsFor(network).getMaxRbtc());
    }

    /**
     * The constants of a named Bitcoin network.
     *
     * <p>Public because the sequencer, which runs outside the node, has to agree with the bridge on
     * every one of them: the same Bitcoin network, the same confirmation depths, the same
     * federation keys. Deriving them separately would be two sources of truth for values that must
     * match exactly.
     */
    public static BridgeConstants constantsFor(String network) {
        return switch (network.toLowerCase(Locale.ROOT)) {
            case "mainnet" -> BridgeMainNetConstants.getInstance();
            case "testnet" -> BridgeTestNetConstants.getInstance();
            case "regtest" -> new BridgeRegTestConstants();
            default -> throw new IllegalArgumentException(
                "There is no Bitcoin network called '" + network + "': expected mainnet, testnet or regtest");
        };
    }
}
