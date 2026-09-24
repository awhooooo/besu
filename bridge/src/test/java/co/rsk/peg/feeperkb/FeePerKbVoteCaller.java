package co.rsk.peg.feeperkb;

import org.hyperledger.besu.datatypes.Address;

/**
 * Created to makes easier the way to get the RSK address of 3 authorized and 1 unauthorized Callers. It
 * contains hex encoded string associated to each caller and return the address of each one.
 */
public enum FeePerKbVoteCaller {
    FIRST_AUTHORIZED("df9106cf6076306afca0dfa372eac067edcff09e"),
    SECOND_AUTHORIZED("cfba43ae5c7a9da52d18b95119f2858c3233bcd0"),
    THIRD_AUTHORIZED("1cafe802d86daf03d15c771e924ada64587c24c9"),
    UNAUTHORIZED("e2a5070b4e2cb77fe22dff05d9dcdc4d3eaa6ead");

    private final String rskAddress;

    FeePerKbVoteCaller(String rskAddress) {
        this.rskAddress = rskAddress;
    }

    public Address getRskAddress() {
        return Address.fromHexString("0x" + rskAddress);
    }
}
