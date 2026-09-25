package co.rsk.peg.lockingcap;

import co.rsk.bitcoinj.core.Coin;
import java.util.Optional;
import co.rsk.peg.host.CallContext;

public interface LockingCapSupport {

    Optional<Coin> getLockingCap();

    boolean increaseLockingCap(CallContext tx, Coin newCap) throws LockingCapIllegalArgumentException;

    void save();
}
