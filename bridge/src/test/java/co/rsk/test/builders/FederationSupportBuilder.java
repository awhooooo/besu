package co.rsk.test.builders;

import static org.mockito.Mockito.mock;

import co.rsk.peg.federation.FederationStorageProvider;
import co.rsk.peg.federation.FederationSupport;
import co.rsk.peg.federation.FederationSupportImpl;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.host.BridgeHost;
import co.rsk.peg.host.InMemoryBridgeHost;

/**
 * Ported from RSKj's test builders. The execution block and the activations RSKj's builder took are the host
 * here: the block number and timestamp come from it, and every RSKIP is active.
 */
public class FederationSupportBuilder {
    private FederationConstants federationConstants;
    private FederationStorageProvider federationStorageProvider;
    private BridgeHost host;

    private FederationSupportBuilder() {
        this.federationConstants = mock(FederationConstants.class);
        this.federationStorageProvider = mock(FederationStorageProvider.class);
        this.host = new InMemoryBridgeHost();
    }

    public static FederationSupportBuilder builder() {
        return new FederationSupportBuilder();
    }

    public FederationSupportBuilder withFederationConstants(FederationConstants federationConstants) {
        this.federationConstants = federationConstants;
        return this;
    }

    public FederationSupportBuilder withFederationStorageProvider(FederationStorageProvider federationStorageProvider) {
        this.federationStorageProvider = federationStorageProvider;
        return this;
    }

    public FederationSupportBuilder withHost(BridgeHost host) {
        this.host = host;
        return this;
    }

    public FederationSupport build() {
        return new FederationSupportImpl(
            federationConstants,
            federationStorageProvider,
            host
        );
    }
}
