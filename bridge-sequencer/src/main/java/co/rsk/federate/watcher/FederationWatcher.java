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
import java.util.Optional;

import co.rsk.bitcoinj.core.Address;
import co.rsk.federate.FederationProvider;
import co.rsk.peg.federation.Federation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notices when the bridge's federations change.
 *
 * <p>Only the addresses are polled. Rebuilding a federation costs a call for each of its members,
 * and a change happens perhaps twice in a chain's life, so asking the cheap question often and the
 * expensive one only when the answer moves is the difference between a handful of calls and a
 * hundred, every time round.
 *
 * <p>Comparing addresses is enough to notice any change that matters. Two different federations
 * cannot share an address: the address is the hash of the redeem script, which names the members.
 *
 * <p>Powpeg watched the best block instead of polling, and its reasoning carries over. It is fine
 * to learn of a change late. Nobody could have been told the federation was different before the
 * chain agreed it was, so no bitcoin can have been sent to a new address that this has not yet
 * seen.
 */
public class FederationWatcher {

    private static final Logger logger = LoggerFactory.getLogger(FederationWatcher.class);

    private final FederationProvider federationProvider;
    private final FederationWatcherListener listener;

    private Federation activeFederation;
    private Federation retiringFederation;
    private Federation proposedFederation;

    public FederationWatcher(FederationProvider federationProvider, FederationWatcherListener listener) {
        this.federationProvider = Objects.requireNonNull(federationProvider, "federationProvider");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Reads the three addresses and reports whichever has moved.
     *
     * <p>The proposed federation is checked first, because a change is validated before it is
     * accepted: a client that is going to have to sign for a proposal should hear about it before
     * anything else moves under it.
     */
    public void updateState() {
        updateProposedFederation();
        updateActiveFederation();
        updateRetiringFederation();
    }

    /** What the watcher currently believes, for the caller that has to start clients on it. */
    public Optional<Federation> getActiveFederation() {
        return Optional.ofNullable(activeFederation);
    }

    public Optional<Federation> getRetiringFederation() {
        return Optional.ofNullable(retiringFederation);
    }

    public Optional<Federation> getProposedFederation() {
        return Optional.ofNullable(proposedFederation);
    }

    private void updateActiveFederation() {
        Address current = federationProvider.getActiveFederationAddress();
        Address previous = addressOf(activeFederation);
        if (current.equals(previous)) {
            return;
        }

        Federation federation = federationProvider.getActiveFederation();
        logger.info("[updateActiveFederation] Active federation changed from {} to {}", previous, current);
        activeFederation = federation;
        listener.onActiveFederationChange(federation);
    }

    private void updateRetiringFederation() {
        Optional<Address> current = federationProvider.getRetiringFederationAddress();
        Optional<Address> previous = Optional.ofNullable(addressOf(retiringFederation));
        if (current.equals(previous)) {
            return;
        }

        Federation federation = current.isEmpty() ? null : federationProvider.getRetiringFederation().orElse(null);
        logger.info("[updateRetiringFederation] Retiring federation changed from {} to {}",
            previous.orElse(null), current.orElse(null));
        retiringFederation = federation;
        listener.onRetiringFederationChange(federation);
    }

    private void updateProposedFederation() {
        Optional<Address> current = federationProvider.getProposedFederationAddress();
        Optional<Address> previous = Optional.ofNullable(addressOf(proposedFederation));
        if (current.equals(previous)) {
            return;
        }

        Federation federation = current.isEmpty() ? null : federationProvider.getProposedFederation().orElse(null);
        logger.info("[updateProposedFederation] Proposed federation changed from {} to {}",
            previous.orElse(null), current.orElse(null));
        proposedFederation = federation;
        listener.onProposedFederationChange(federation);
    }

    private static Address addressOf(Federation federation) {
        return federation == null ? null : federation.getAddress();
    }
}
