package co.rsk.peg.feeperkb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.BridgeSerializationUtils;
import co.rsk.peg.PegTestUtils;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.feeperkb.constants.FeePerKbConstants;
import co.rsk.peg.feeperkb.constants.FeePerKbMainNetConstants;
import co.rsk.peg.vote.ABICallElection;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeePerKbSupportImplTest {

    private static final String SET_FEE_PER_KB_ABI_FUNCTION = "setFeePerKb";

    private FeePerKbStorageProvider storageProvider;
    private FeePerKbConstants feePerKbConstants;
    private FeePerKbSupportImpl feePerKbSupport;

    @BeforeEach
    void setUp() {
        storageProvider = mock(FeePerKbStorageProvider.class);
        feePerKbConstants = FeePerKbMainNetConstants.getInstance();
        feePerKbSupport = new FeePerKbSupportImpl(feePerKbConstants, storageProvider);
    }

    @Test
    void getFeePerKb() {
        Optional<Coin> currentFeePerKb = Optional.of(Coin.valueOf(50_000L));
        when(storageProvider.getFeePerKb()).thenReturn(currentFeePerKb);

        Coin actualResult = feePerKbSupport.getFeePerKb();

        Coin expectedResult = currentFeePerKb.get();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void getFeePerKb_nullInStorageProvider_shouldReturnGenesisFeePerKb() {
        Coin actualResult = feePerKbSupport.getFeePerKb();

        Coin expectedResult = feePerKbConstants.getGenesisFeePerKb();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_withUnauthorizedSignature_shouldReturnUnauthorizedCallerResponseCode() {
        CallContext tx = getTransactionFromUnauthorizedCaller();
        Coin feePerKbVote = Coin.valueOf(50_000L);

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, feePerKbVote);

        Integer expectedResult = FeePerKbResponseCode.UNAUTHORIZED_CALLER.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_negativeFeePerKbValue_shouldReturnNegativeFeeVotedResponseCode() {
        CallContext tx = getTransactionFromAuthorizedCaller();
        Coin negativeFeePerKbVote = Coin.NEGATIVE_SATOSHI;

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, negativeFeePerKbVote);

        Integer expectedResult = FeePerKbResponseCode.NEGATIVE_FEE_VOTED.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_aboveMaxFeePerKbValue_shouldReturnExcessiveFeeVotedResponseCode() {
        CallContext tx = getTransactionFromAuthorizedCaller();
        Coin maxFeePerKb = feePerKbConstants.getMaxFeePerKb();
        Coin excessiveFeePerKbVote = maxFeePerKb.add(Coin.SATOSHI);

        Integer aboveMaxValueVotedResult = feePerKbSupport.voteFeePerKbChange(tx, excessiveFeePerKbVote);

        Integer expectedResult = FeePerKbResponseCode.EXCESSIVE_FEE_VOTED.getCode();
        assertEquals(expectedResult, aboveMaxValueVotedResult);
    }

    @Test
    void voteFeePerKbChange_zeroFeePerKbValue_shouldReturnNegativeFeeVotedResponseCode() {
        CallContext tx = getTransactionFromAuthorizedCaller();
        Coin zeroFeePerKb = Coin.ZERO;

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, zeroFeePerKb);

        Integer expectedResult = FeePerKbResponseCode.NEGATIVE_FEE_VOTED.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_veryLowFeePerKbValue_shouldReturnSuccessfulFeeVotedResponseCode() {
        CallContext tx = getTransactionFromAuthorizedCaller();
        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        ABICallElection feePerKbElection = new ABICallElection(authorizer);
        when(storageProvider.getFeePerKbElection(authorizer)).thenReturn(feePerKbElection);
        Coin veryLowFeePerKb = Coin.SATOSHI;

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, veryLowFeePerKb);

        Integer expectedResult = FeePerKbResponseCode.SUCCESSFUL_VOTE.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_equalMaxFeePerKbValue_shouldReturnSuccessfulFeeVotedResponseCode() {
        CallContext tx = getTransactionFromAuthorizedCaller();
        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        ABICallElection feePerKbElection = new ABICallElection(authorizer);
        when(storageProvider.getFeePerKbElection(authorizer)).thenReturn(feePerKbElection);
        Coin maxFeePerKb = feePerKbConstants.getMaxFeePerKb();

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, maxFeePerKb);

        Integer expectedResult = FeePerKbResponseCode.SUCCESSFUL_VOTE.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_repeatedVote_sameRskAddressSameFeePerKbValue_shouldReturnUnsuccessfulFeeVotedResponseCode() {
        CallContext tx = getTransactionFromAuthorizedCaller();

        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        Coin feePerKb = Coin.valueOf(50_000L);
        Address previousVoter = this.getAuthorizedRskAddresses().get(0);
        ABICallElection feePerKbElection = getAbiCallElectionWithExistingVote(authorizer, feePerKb, previousVoter);
        when(storageProvider.getFeePerKbElection(authorizer)).thenReturn(feePerKbElection);

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, feePerKb);

        Integer expectedResult = FeePerKbResponseCode.UNSUCCESSFUL_VOTE.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_repeatedVote_sameRskAddressDifferentFeePerKbValue_shouldReturnSuccessfulFeeVotedResponseCode() {
        CallContext tx = getTransactionFromAuthorizedCaller();

        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        Coin firstFeePerKb = Coin.valueOf(50_000L);
        Address previousVoter = this.getAuthorizedRskAddresses().get(0);
        ABICallElection feePerKbElection = getAbiCallElectionWithExistingVote(authorizer, firstFeePerKb, previousVoter);
        when(storageProvider.getFeePerKbElection(authorizer)).thenReturn(feePerKbElection);

        Coin secondFeePerKb = feePerKbConstants.getMaxFeePerKb();

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, secondFeePerKb);

        Integer expectedResult = FeePerKbResponseCode.SUCCESSFUL_VOTE.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_winnerFeePerKbValue_shouldReturnSuccessfulFeeVotedResponseCode() {
        CallContext tx = getTransactionFromAuthorizedCaller();

        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        Coin feePerKb = Coin.valueOf(50_000L);

        Address previousVoter = this.getAuthorizedRskAddresses().get(1);
        ABICallElection feePerKbElection = getAbiCallElectionWithExistingVote(authorizer, feePerKb, previousVoter);
        when(storageProvider.getFeePerKbElection(authorizer)).thenReturn(feePerKbElection);

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, feePerKb);

        Integer expectedResult = FeePerKbResponseCode.SUCCESSFUL_VOTE.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_nullFeeThrows() {
        CallContext tx = getTransactionFromAuthorizedCaller();

        assertThrows(NullPointerException.class, () -> feePerKbSupport.voteFeePerKbChange(tx, null));
        verify(storageProvider, never()).setFeePerKb(any());
    }

    @Test
    void save() {
        doNothing().when(storageProvider).save();

        feePerKbSupport.save();

        verify(storageProvider, times(1)).save();
    }

    private CallContext getTransactionFromUnauthorizedCaller() {
        return PegTestUtils.callFrom(this.getUnauthorizedRskAddress());
    }

    private CallContext getTransactionFromAuthorizedCaller() {
        Address authorizedRskAddress = this.getAuthorizedRskAddresses().get(0);
        return PegTestUtils.callFrom(authorizedRskAddress);
    }

    private Address getUnauthorizedRskAddress(){
        return Address.fromHexString("0xe2a5070b4e2cb77fe22dff05d9dcdc4d3eaa6ead");
    }

    private List<Address> getAuthorizedRskAddresses(){
        return Stream.of(
            "0xdf9106cf6076306afca0dfa372eac067edcff09e",
            "0xcfba43ae5c7a9da52d18b95119f2858c3233bcd0",
            "0x1cafe802d86daf03d15c771e924ada64587c24c9"
        ).map(Address::fromHexString).collect(Collectors.toList());
    }

    private ABICallElection getAbiCallElectionWithExistingVote(
        AddressBasedAuthorizer authorizer,
        Coin feePerKbVote,
        Address voter) {

        byte[] feePerKbVoteSerialized = BridgeSerializationUtils.serializeCoin(feePerKbVote);
        ABICallSpec feeVote = new ABICallSpec(SET_FEE_PER_KB_ABI_FUNCTION, new byte[][]{feePerKbVoteSerialized});

        List<Address> voters = Collections.singletonList(voter);
        Map<ABICallSpec, List<Address>> existingVotes = new HashMap<>();
        existingVotes.put(feeVote, voters);

        return new ABICallElection(authorizer, existingVotes);
    }
}
