package co.rsk.peg;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.constants.BridgeTestNetConstants;
import co.rsk.peg.btcLockSender.BtcLockSender;
import co.rsk.peg.btcLockSender.BtcLockSender.TxSenderAddressType;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.btcLockSender.P2pkhBtcLockSender;
import co.rsk.peg.pegininstructions.PeginInstructionsException;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import co.rsk.peg.utils.PublicKeys;
import co.rsk.peg.pegininstructions.PeginInstructionsVersion1;
import java.util.Optional;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PeginInformationTest {

    private static BridgeConstants bridgeConstants;
    private static NetworkParameters networkParameters;

    @BeforeAll
     static void setup() {
        bridgeConstants = new BridgeRegTestConstants();
        networkParameters = bridgeConstants.getBtcParams();
    }

    @Test
    void parse_fromBtcLockSender() throws PeginInstructionsException {
        // Arrange
        BtcECKey key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromBtcLockSender = PublicKeys.addressOf(key);
        Address btcRefundAddressFromBtcLockSender = key.toAddress(networkParameters);
        TxSenderAddressType senderBtcAddressType = TxSenderAddressType.P2PKH;
        BtcTransaction btcTx = new BtcTransaction(networkParameters);

        BtcLockSender btcLockSenderMock = mock(P2pkhBtcLockSender.class);
        when(btcLockSenderMock.getRskAddress()).thenReturn(rskDestinationAddressFromBtcLockSender);
        when(btcLockSenderMock.getBTCAddress()).thenReturn(btcRefundAddressFromBtcLockSender);
        when(btcLockSenderMock.getTxSenderAddressType()).thenReturn(senderBtcAddressType);

        BtcLockSenderProvider btcLockSenderProviderMock = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProviderMock.tryGetBtcLockSender(btcTx))
            .thenReturn(Optional.of(btcLockSenderMock));

        PeginInstructionsProvider peginInstructionsProviderMock = mock(
            PeginInstructionsProvider.class);

        // Act
        PeginInformation peginInformation = new PeginInformation(
            btcLockSenderProviderMock,
            peginInstructionsProviderMock
        );
        peginInformation.parse(btcTx);

        // Assert
        Assertions.assertEquals(0, peginInformation.getProtocolVersion());
        Assertions.assertEquals(rskDestinationAddressFromBtcLockSender, peginInformation.getRskDestinationAddress());
        Assertions.assertEquals(btcRefundAddressFromBtcLockSender, peginInformation.getBtcRefundAddress());
        Assertions.assertEquals(btcRefundAddressFromBtcLockSender, peginInformation.getSenderBtcAddress());
        Assertions.assertEquals(senderBtcAddressType, peginInformation.getSenderBtcAddressType());
    }

    @Test
    void parse_fromPeginInstructions() throws PeginInstructionsException {
        // Arrange
        BtcECKey address1Key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromBtcLockSender = PublicKeys.addressOf(address1Key);
        Address btcRefundAddressFromBtcLockSender = address1Key.toAddress(networkParameters);
        TxSenderAddressType senderBtcAddressType = TxSenderAddressType.P2PKH;
        BtcTransaction btcTx = new BtcTransaction(networkParameters);

        BtcLockSender btcLockSenderMock = mock(P2pkhBtcLockSender.class);
        when(btcLockSenderMock.getRskAddress()).thenReturn(rskDestinationAddressFromBtcLockSender);
        when(btcLockSenderMock.getBTCAddress()).thenReturn(btcRefundAddressFromBtcLockSender);
        when(btcLockSenderMock.getTxSenderAddressType()).thenReturn(senderBtcAddressType);

        BtcLockSenderProvider btcLockSenderProviderMock = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProviderMock.tryGetBtcLockSender(btcTx))
            .thenReturn(Optional.of(btcLockSenderMock));

        BtcECKey address2Key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromPeginInstructions = PublicKeys.addressOf(address2Key);
        Address btcRefundAddressFromPeginInstructions = address2Key.toAddress(networkParameters);

        PeginInstructionsVersion1 peginInstructionsMock = mock(PeginInstructionsVersion1.class);
        when(peginInstructionsMock.getProtocolVersion()).thenReturn(1);
        when(peginInstructionsMock.getRskDestinationAddress())
            .thenReturn(rskDestinationAddressFromPeginInstructions);
        when(peginInstructionsMock.getBtcRefundAddress())
            .thenReturn(Optional.of(btcRefundAddressFromPeginInstructions));

        PeginInstructionsProvider peginInstructionsProviderMock = mock(PeginInstructionsProvider.class);
        when(peginInstructionsProviderMock.buildPeginInstructions(btcTx))
            .thenReturn(Optional.of(peginInstructionsMock));

        // Act
        PeginInformation peginInformation = new PeginInformation(
            btcLockSenderProviderMock,
            peginInstructionsProviderMock
        );
        peginInformation.parse(btcTx);

        // Assert
        Assertions.assertEquals(1, peginInformation.getProtocolVersion());
        Assertions.assertEquals(rskDestinationAddressFromPeginInstructions, peginInformation.getRskDestinationAddress());
        Assertions.assertEquals(btcRefundAddressFromPeginInstructions, peginInformation.getBtcRefundAddress());
        Assertions.assertEquals(btcRefundAddressFromBtcLockSender, peginInformation.getSenderBtcAddress());
        Assertions.assertEquals(senderBtcAddressType, peginInformation.getSenderBtcAddressType());
        Assertions.assertNotEquals(rskDestinationAddressFromBtcLockSender, peginInformation.getRskDestinationAddress());
        Assertions.assertNotEquals(btcRefundAddressFromBtcLockSender, peginInformation.getBtcRefundAddress());
    }

    @Test
    void parse_fromPeginInstructions_withoutBtcLockSender() throws PeginInstructionsException {
        // Arrange
        BtcTransaction btcTx = new BtcTransaction(networkParameters);
        BtcLockSenderProvider btcLockSenderProviderMock = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProviderMock.tryGetBtcLockSender(btcTx)).thenReturn(Optional.empty());

        BtcECKey address2Key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromPeginInstructions = PublicKeys.addressOf(address2Key);
        Address btcRefundAddressFromPeginInstructions = address2Key.toAddress(networkParameters);

        PeginInstructionsVersion1 peginInstructionsMock = mock(PeginInstructionsVersion1.class);
        when(peginInstructionsMock.getProtocolVersion()).thenReturn(1);
        when(peginInstructionsMock.getRskDestinationAddress())
            .thenReturn(rskDestinationAddressFromPeginInstructions);
        when(peginInstructionsMock.getBtcRefundAddress())
            .thenReturn(Optional.of(btcRefundAddressFromPeginInstructions));

        PeginInstructionsProvider peginInstructionsProviderMock = mock(PeginInstructionsProvider.class);
        when(peginInstructionsProviderMock.buildPeginInstructions(btcTx))
            .thenReturn(Optional.of(peginInstructionsMock));

        // Act
        PeginInformation peginInformation = new PeginInformation(
            btcLockSenderProviderMock,
            peginInstructionsProviderMock
        );
        peginInformation.parse(btcTx);

        // Assert
        Assertions.assertEquals(1, peginInformation.getProtocolVersion());
        Assertions.assertEquals(rskDestinationAddressFromPeginInstructions, peginInformation.getRskDestinationAddress());
        Assertions.assertEquals(btcRefundAddressFromPeginInstructions, peginInformation.getBtcRefundAddress());
        Assertions.assertNull(peginInformation.getSenderBtcAddress());
        Assertions.assertEquals(TxSenderAddressType.UNKNOWN, peginInformation.getSenderBtcAddressType());
    }

    @Test
    void parse_fromPeginInstructions_withoutBtcRefundAddress() throws PeginInstructionsException {
        // Arrange
        BtcECKey address1Key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromBtcLockSender = PublicKeys.addressOf(address1Key);
        Address btcRefundAddressFromBtcLockSender = address1Key.toAddress(networkParameters);
        TxSenderAddressType senderBtcAddressType = TxSenderAddressType.P2PKH;
        BtcTransaction btcTx = new BtcTransaction(networkParameters);

        BtcLockSender btcLockSenderMock = mock(P2pkhBtcLockSender.class);
        when(btcLockSenderMock.getRskAddress()).thenReturn(rskDestinationAddressFromBtcLockSender);
        when(btcLockSenderMock.getBTCAddress()).thenReturn(btcRefundAddressFromBtcLockSender);
        when(btcLockSenderMock.getTxSenderAddressType()).thenReturn(senderBtcAddressType);

        BtcLockSenderProvider btcLockSenderProviderMock = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProviderMock.tryGetBtcLockSender(btcTx))
            .thenReturn(Optional.of(btcLockSenderMock));

        BtcECKey address2Key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromPeginInstructions = PublicKeys.addressOf(address2Key);

        PeginInstructionsVersion1 peginInstructionsMock = mock(PeginInstructionsVersion1.class);
        when(peginInstructionsMock.getProtocolVersion()).thenReturn(1);
        when(peginInstructionsMock.getRskDestinationAddress())
            .thenReturn(rskDestinationAddressFromPeginInstructions);
        when(peginInstructionsMock.getBtcRefundAddress()).thenReturn(Optional.empty());

        PeginInstructionsProvider peginInstructionsProviderMock = mock(
            PeginInstructionsProvider.class);
        when(peginInstructionsProviderMock.buildPeginInstructions(btcTx))
            .thenReturn(Optional.of(peginInstructionsMock));

        // Act
        PeginInformation peginInformation = new PeginInformation(
            btcLockSenderProviderMock,
            peginInstructionsProviderMock
        );
        peginInformation.parse(btcTx);

        // Assert
        Assertions.assertEquals(1, peginInformation.getProtocolVersion());
        Assertions.assertEquals(rskDestinationAddressFromPeginInstructions, peginInformation.getRskDestinationAddress());
        Assertions.assertEquals(btcRefundAddressFromBtcLockSender, peginInformation.getBtcRefundAddress());
        Assertions.assertEquals(btcRefundAddressFromBtcLockSender, peginInformation.getSenderBtcAddress());
        Assertions.assertEquals(senderBtcAddressType, peginInformation.getSenderBtcAddressType());
        Assertions.assertNotEquals(rskDestinationAddressFromBtcLockSender, peginInformation.getRskDestinationAddress());
    }

    @Test
    void parse_fromPeginInstructions_withoutBtcLockSender_withoutBtcRefundAddress() throws PeginInstructionsException {
        // Arrange
        BtcTransaction btcTx = new BtcTransaction(networkParameters);
        BtcLockSenderProvider btcLockSenderProviderMock = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProviderMock.tryGetBtcLockSender(btcTx))
            .thenReturn(Optional.empty());

        BtcECKey address2Key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromPeginInstructions = PublicKeys.addressOf(address2Key);

        PeginInstructionsVersion1 peginInstructionsMock = mock(PeginInstructionsVersion1.class);
        when(peginInstructionsMock.getProtocolVersion()).thenReturn(1);
        when(peginInstructionsMock.getRskDestinationAddress())
            .thenReturn(rskDestinationAddressFromPeginInstructions);
        when(peginInstructionsMock.getBtcRefundAddress())
            .thenReturn(Optional.empty());

        PeginInstructionsProvider peginInstructionsProviderMock = mock(PeginInstructionsProvider.class);
        when(peginInstructionsProviderMock.buildPeginInstructions(btcTx))
            .thenReturn(Optional.of(peginInstructionsMock));

        // Act
        PeginInformation peginInformation = new PeginInformation(
            btcLockSenderProviderMock,
            peginInstructionsProviderMock
        );
        peginInformation.parse(btcTx);

        // Assert
        Assertions.assertEquals(1, peginInformation.getProtocolVersion());
        Assertions.assertEquals(rskDestinationAddressFromPeginInstructions, peginInformation.getRskDestinationAddress());
        Assertions.assertNull(peginInformation.getBtcRefundAddress());
        Assertions.assertNull(peginInformation.getSenderBtcAddress());
        Assertions.assertEquals(TxSenderAddressType.UNKNOWN, peginInformation.getSenderBtcAddressType());
    }

    @Test
    void parse_fromPeginInstructions_invalidProtocolVersion() throws PeginInstructionsException {
        // Arrange
        BtcECKey address1Key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromBtcLockSender = PublicKeys.addressOf(address1Key);
        Address btcRefundAddressFromBtcLockSender = address1Key.toAddress(networkParameters);
        BtcTransaction btcTx = new BtcTransaction(networkParameters);

        BtcLockSender btcLockSenderMock = mock(P2pkhBtcLockSender.class);
        when(btcLockSenderMock.getRskAddress()).thenReturn(rskDestinationAddressFromBtcLockSender);
        when(btcLockSenderMock.getBTCAddress()).thenReturn(btcRefundAddressFromBtcLockSender);

        BtcLockSenderProvider btcLockSenderProviderMock = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProviderMock.tryGetBtcLockSender(btcTx))
            .thenReturn(Optional.of(btcLockSenderMock));

        BtcECKey address2Key = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskDestinationAddressFromPeginInstructions = PublicKeys.addressOf(address2Key);

        PeginInstructionsVersion1 peginInstructionsMock = mock(PeginInstructionsVersion1.class);
        when(peginInstructionsMock.getProtocolVersion()).thenReturn(0);
        when(peginInstructionsMock.getRskDestinationAddress())
            .thenReturn(rskDestinationAddressFromPeginInstructions);

        PeginInstructionsProvider peginInstructionsProviderMock = mock(
            PeginInstructionsProvider.class);
        when(peginInstructionsProviderMock.buildPeginInstructions(btcTx))
            .thenReturn(Optional.of(peginInstructionsMock));

        // Act
        PeginInformation peginInformation = new PeginInformation(
            btcLockSenderProviderMock,
            peginInstructionsProviderMock
        );

        Assertions.assertThrows(PeginInstructionsException.class, () -> peginInformation.parse(btcTx));
    }

    @Test
    void parse_bech32_withPeginInstructions() {
        // Arrange
        String rawTx = "02000000000101cf8b3b2baa22df50b1959d83b2f279ef231fb7cf2009ebfa35644d9e1f0184930200000000fdffffff02706408000000000017a9146e4b5ae85d86e4db0e6e5db09f8c276328cdbf3f87ec5cd40500000000160014d7aa00421cd50c8f282dc7d32992a5e2932a92f3024730440220313d11b58bd2861e4a2f8b2d1b644250569e35c946fcba426de94ed28974f12102207b9074796eea9f823a2d56239f49674c4ec34d40f519b3babd5eba44f94495eb012102acbad9efed3a451f646b93b5fd37a796c1758875cc33eed1626d4ed673d00a9b5abd2600";
        BtcTransaction btcTx = new BtcTransaction(BridgeTestNetConstants.getInstance().getBtcParams(), Hex.decode(rawTx));

        BtcLockSenderProvider btcLockSenderProviderMock = new BtcLockSenderProvider();
        PeginInstructionsProvider peginInstructionsProvider = new PeginInstructionsProvider();

        // Act
        PeginInformation peginInformation = new PeginInformation(
            btcLockSenderProviderMock,
            peginInstructionsProvider
        );

        // assert
        Assertions.assertDoesNotThrow(() -> {
            peginInformation.parse(btcTx);
            Assertions.assertEquals(0, peginInformation.getProtocolVersion());
            Assertions.assertEquals(TxSenderAddressType.UNKNOWN, peginInformation.getSenderBtcAddressType());
            Assertions.assertNull(peginInformation.getBtcRefundAddress());
            Assertions.assertNull(peginInformation.getSenderBtcAddress());
            Assertions.assertNull(peginInformation.getRskDestinationAddress());
        });
    }
}
