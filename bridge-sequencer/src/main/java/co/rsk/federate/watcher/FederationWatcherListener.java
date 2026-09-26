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

import co.rsk.peg.federation.Federation;

/** Told when the bridge's federations change, so that the clients can follow. */
public interface FederationWatcherListener {

    void onActiveFederationChange(Federation newActiveFederation);

    /** @param newRetiringFederation null once there is no retiring federation left */
    void onRetiringFederationChange(Federation newRetiringFederation);

    /** @param newProposedFederation null once the proposal has been accepted or abandoned */
    void onProposedFederationChange(Federation newProposedFederation);
}
