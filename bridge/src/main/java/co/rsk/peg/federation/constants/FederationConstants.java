package co.rsk.peg.federation.constants;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public abstract class FederationConstants {
    protected NetworkParameters btcParams;

    protected List<BtcECKey> genesisFederationPublicKeys;
    protected Instant genesisFederationCreationTime;

    protected AddressBasedAuthorizer federationChangeAuthorizer;
    protected long validationPeriodDurationInBlocks;
    protected long federationActivationAge;
    protected long fundsMigrationAgeSinceActivationBegin;
    protected long fundsMigrationAgeSinceActivationEnd;

    protected long erpFedActivationDelay;
    protected List<BtcECKey> erpFedPubKeysList;

    public NetworkParameters getBtcParams() { return btcParams; }

    public List<BtcECKey> getGenesisFederationPublicKeys() {
        return new ArrayList<>(genesisFederationPublicKeys);
    }

    public Instant getGenesisFederationCreationTime() {
        return genesisFederationCreationTime;
    }

    public long getValidationPeriodDurationInBlocks() { return validationPeriodDurationInBlocks; }

    public long getFederationActivationAge() {
        return federationActivationAge;
    }

    public long getFundsMigrationAgeSinceActivationBegin() {
        return fundsMigrationAgeSinceActivationBegin;
    }

    public long getFundsMigrationAgeSinceActivationEnd() {
        return fundsMigrationAgeSinceActivationEnd;
    }

    public AddressBasedAuthorizer getFederationChangeAuthorizer() { return federationChangeAuthorizer; }

    public long getErpFedActivationDelay() {
        return erpFedActivationDelay;
    }

    public List<BtcECKey> getErpFedPubKeysList() {
        return new ArrayList<>(erpFedPubKeysList);
    }
}
