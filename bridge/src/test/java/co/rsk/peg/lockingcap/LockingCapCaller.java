package co.rsk.peg.lockingcap;

import org.hyperledger.besu.datatypes.Address;

public enum LockingCapCaller {
    FIRST_AUTHORIZED("d5c514b5d37ba2b9281b85ba18b1693f09bb5d0f"),
    SECOND_AUTHORIZED("7eca14ab65a1013d0c9a96e3070c57c8f89c5efc"),
    THIRD_AUTHORIZED("13dca76afb7d6d0bac34b87d2fcbc91ce5104c26"),
    UNAUTHORIZED("e2a5070b4e2cb77fe22dff05d9dcdc4d3eaa6ead");

    private final String rskAddress;

    LockingCapCaller(String rskAddress) {
        this.rskAddress = rskAddress;
    }

    public Address getRskAddress() {
        return Address.fromHexString("0x" + rskAddress);
    }
}
