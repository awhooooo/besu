package co.rsk.peg.federation;

import co.rsk.bitcoinj.core.UTXO;
import co.rsk.bitcoinj.script.Script;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.vote.ABICallElection;
import co.rsk.peg.vote.AddressBasedAuthorizer;

import java.util.List;
import java.util.Optional;

public interface FederationStorageProvider {

    List<UTXO> getNewFederationBtcUTXOs();
    List<UTXO> getOldFederationBtcUTXOs();

    Federation getNewFederation(FederationConstants federationConstants);
    void setNewFederation(Federation federation);

    Federation getOldFederation(FederationConstants federationConstants);
    void setOldFederation(Federation federation);

    PendingFederation getPendingFederation();
    void setPendingFederation(PendingFederation federation);

    Optional<Federation> getProposedFederation(FederationConstants federationConstants);
    void setProposedFederation(Federation proposedFederation);

    ABICallElection getFederationElection(AddressBasedAuthorizer authorizer);

    Optional<Long> getActiveFederationCreationBlockHeight();
    void setActiveFederationCreationBlockHeight(long activeFederationCreationBlockHeight);

    Optional<Long> getNextFederationCreationBlockHeight();
    void setNextFederationCreationBlockHeight(long nextFederationCreationBlockHeight);
    void clearNextFederationCreationBlockHeight();

    Optional<Script> getLastRetiredFederationP2SHScript();
    void setLastRetiredFederationP2SHScript(Script lastRetiredFederationP2SHScript);

    void save();
}
