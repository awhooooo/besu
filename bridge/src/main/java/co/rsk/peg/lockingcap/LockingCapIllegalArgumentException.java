package co.rsk.peg.lockingcap;

import co.rsk.peg.exception.VMException;

public class LockingCapIllegalArgumentException extends VMException {

    public LockingCapIllegalArgumentException() {
        super();
    }

    public LockingCapIllegalArgumentException(String message) {
        super(message);
    }

    public LockingCapIllegalArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
