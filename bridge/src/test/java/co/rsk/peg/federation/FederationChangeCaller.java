package co.rsk.peg.federation;

import org.hyperledger.besu.datatypes.Address;

/** The addresses of the mainnet federation change authorizers, ported from RSKj's test enum. */
public enum FederationChangeCaller {

    FIRST_AUTHORIZED("69fd43f7981f6b25c9c78f5d8bbd4b3ba508e952"),
    SECOND_AUTHORIZED("8390b171f5df6824a2885ea7ef576601ac5bdcc6"),
    THIRD_AUTHORIZED("92bfbc1239c5fe51a6e3838c6905ee03bfa2284b"),
    FOURTH_AUTHORIZED("4e8bc3d0f0c1541fd63c8ea90ac2afb1fe8421f3"),
    FIFTH_AUTHORIZED("77174911429a562dbfb798c32b90112fc23af586"),
    UNAUTHORIZED("f49060c32d922fd7e3533aa434e4576ba411db0a");

    private final String address;

    FederationChangeCaller(String address) {
        this.address = address;
    }

    public Address getAddress() {
        return Address.fromHexString("0x" + address);
    }

}
