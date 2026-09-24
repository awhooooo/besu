package co.rsk.test.builders;

import static org.mockito.Mockito.mock;

import co.rsk.bitcoinj.core.Context;
import co.rsk.peg.BridgeStorageProvider;
import co.rsk.peg.BridgeSupport;
import co.rsk.peg.BtcBlockStoreWithCache.Factory;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.federation.FederationSupport;
import co.rsk.peg.feeperkb.FeePerKbSupport;
import co.rsk.peg.host.BridgeHost;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.lockingcap.LockingCapSupport;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import co.rsk.peg.utils.BridgeEventLogger;

/**
 * Ported from RSKj's test builders. The repository, the execution block and the activations RSKj's builder took
 * are the host here: storage, the block number and the block timestamp come from it, and every RSKIP is active.
 * The whitelist support went with the lock whitelist in step 4d.
 */
public class BridgeSupportBuilder {
    private BridgeConstants bridgeConstants;
    private BridgeStorageProvider provider;
    private BridgeEventLogger eventLogger;
    private BtcLockSenderProvider btcLockSenderProvider;
    private PeginInstructionsProvider peginInstructionsProvider;
    private BridgeHost host;
    private FeePerKbSupport feePerKbSupport;
    private FederationSupport federationSupport;
    private LockingCapSupport lockingCapSupport;
    private Factory btcBlockStoreFactory;

    private BridgeSupportBuilder() {
        this.bridgeConstants = mock(BridgeConstants.class);
        this.provider = mock(BridgeStorageProvider.class);
        this.eventLogger = mock(BridgeEventLogger.class);
        this.btcLockSenderProvider = mock(BtcLockSenderProvider.class);
        this.peginInstructionsProvider = mock(PeginInstructionsProvider.class);
        this.host = new InMemoryBridgeHost();
        this.feePerKbSupport = mock(FeePerKbSupport.class);
        this.federationSupport = mock(FederationSupport.class);
        this.lockingCapSupport = mock(LockingCapSupport.class);
        this.btcBlockStoreFactory = mock(Factory.class);
    }

    public static BridgeSupportBuilder builder() {
        return new BridgeSupportBuilder();
    }

    public BridgeSupportBuilder withBridgeConstants(BridgeConstants bridgeConstants) {
        this.bridgeConstants = bridgeConstants;
        return this;
    }

    public BridgeSupportBuilder withProvider(BridgeStorageProvider provider) {
        this.provider = provider;
        return this;
    }

    public BridgeSupportBuilder withEventLogger(BridgeEventLogger eventLogger) {
        this.eventLogger = eventLogger;
        return this;
    }

    public BridgeSupportBuilder withBtcLockSenderProvider(BtcLockSenderProvider btcLockSenderProvider) {
        this.btcLockSenderProvider = btcLockSenderProvider;
        return this;
    }

    public BridgeSupportBuilder withPeginInstructionsProvider(PeginInstructionsProvider peginInstructionsProvider) {
        this.peginInstructionsProvider = peginInstructionsProvider;
        return this;
    }

    public BridgeSupportBuilder withHost(BridgeHost host) {
        this.host = host;
        return this;
    }

    public BridgeSupportBuilder withFeePerKbSupport(FeePerKbSupport feePerKbSupport) {
        this.feePerKbSupport = feePerKbSupport;
        return this;
    }

    public BridgeSupportBuilder withFederationSupport(FederationSupport federationSupport) {
        this.federationSupport = federationSupport;
        return this;
    }

    public BridgeSupportBuilder withLockingCapSupport(LockingCapSupport lockingCapSupport) {
        this.lockingCapSupport = lockingCapSupport;
        return this;
    }

    public BridgeSupportBuilder withBtcBlockStoreFactory(Factory btcBlockStoreFactory) {
        this.btcBlockStoreFactory = btcBlockStoreFactory;
        return this;
    }

    public BridgeSupport build() {
        Context context = new Context(bridgeConstants.getBtcParams());

        return new BridgeSupport(
            bridgeConstants,
            provider,
            eventLogger,
            btcLockSenderProvider,
            peginInstructionsProvider,
            host,
            context,
            feePerKbSupport,
            federationSupport,
            lockingCapSupport,
            btcBlockStoreFactory
        );
    }
}
