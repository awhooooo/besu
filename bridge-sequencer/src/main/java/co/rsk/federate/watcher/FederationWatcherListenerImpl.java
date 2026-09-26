/*
 * This file is part of RskJ
 * Copyright (C) 2018 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.federate.watcher;

import java.util.Objects;

import co.rsk.federate.BtcToRskClient;
import co.rsk.federate.bitcoin.BitcoinWrapper;
import co.rsk.federate.btcreleaseclient.BtcReleaseClient;
import co.rsk.peg.federation.Federation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Points the clients at whichever federations the bridge currently has.
 *
 * <p>There are two peg-in clients because during a change there are two addresses to watch, each
 * with its own record of which transactions it is waiting to prove. The release client takes them
 * all: signing is decided per peg-out, by which federation the inputs spend, so it can hold the
 * outgoing and incoming federations at once and sign only what its keys are good for.
 *
 * <p>Nothing here throws. A change that cannot be followed is logged and the rest is still
 * attempted, because the alternative is a sequencer that stops relaying a peg-in it could have
 * relayed on account of a federation it was never going to sign for.
 */
public class FederationWatcherListenerImpl implements FederationWatcherListener {

    private static final Logger logger = LoggerFactory.getLogger(FederationWatcherListenerImpl.class);

    private final BtcToRskClient activeFederationClient;
    private final BtcToRskClient retiringFederationClient;
    private final BtcReleaseClient releaseClient;
    private final BitcoinWrapper bitcoinWrapper;

    public FederationWatcherListenerImpl(
        BtcToRskClient activeFederationClient,
        BtcToRskClient retiringFederationClient,
        BtcReleaseClient releaseClient,
        BitcoinWrapper bitcoinWrapper) {
        this.activeFederationClient = Objects.requireNonNull(activeFederationClient, "activeFederationClient");
        this.retiringFederationClient =
            Objects.requireNonNull(retiringFederationClient, "retiringFederationClient");
        this.releaseClient = Objects.requireNonNull(releaseClient, "releaseClient");
        this.bitcoinWrapper = Objects.requireNonNull(bitcoinWrapper, "bitcoinWrapper");
    }

    @Override
    public void onActiveFederationChange(Federation newActiveFederation) {
        Objects.requireNonNull(newActiveFederation, "There is always an active federation");
        point(activeFederationClient, newActiveFederation, "active");
    }

    @Override
    public void onRetiringFederationChange(Federation newRetiringFederation) {
        if (newRetiringFederation == null) {
            logger.info("[onRetiringFederationChange] Nothing left to migrate; stopping the retiring client");
            retiringFederationClient.stop();
            return;
        }
        point(retiringFederationClient, newRetiringFederation, "retiring");
    }

    @Override
    public void onProposedFederationChange(Federation newProposedFederation) {
        if (newProposedFederation == null) {
            logger.info("[onProposedFederationChange] The proposal is over");
            return;
        }

        try {
            // So that this sequencer can sign the validation spend, if the keys it holds are the
            // proposed federation's. If they are not, the release client watches and signs
            // nothing, which is what a member of the outgoing federation should do.
            releaseClient.start(newProposedFederation);

            // And so that both validation transactions are noticed on bitcoin and registered with
            // the bridge, which is what carries the ceremony forward: the bridge only learns the
            // funding confirmed when somebody registers it, and only completes the change when
            // somebody registers the spend. Either is open to anyone, so the active client does
            // it rather than a third one; the proposal has no peg of its own to watch.
            bitcoinWrapper.addFederationListener(newProposedFederation, activeFederationClient);
            activeFederationClient.alsoRelayFor(newProposedFederation);

            logger.info("[onProposedFederationChange] Following the proposed federation {}",
                newProposedFederation.getAddress());
        } catch (Exception e) {
            logger.error("[onProposedFederationChange] Could not follow the proposed federation {}",
                newProposedFederation.getAddress(), e);
        }
    }

    private void point(BtcToRskClient client, Federation federation, String role) {
        try {
            client.stop();
            client.start(federation);
            releaseClient.start(federation);
            logger.info("[point] Clients now following the {} federation {}", role, federation.getAddress());
        } catch (Exception e) {
            logger.error("[point] Could not follow the {} federation {}", role, federation.getAddress(), e);
        }
    }
}
