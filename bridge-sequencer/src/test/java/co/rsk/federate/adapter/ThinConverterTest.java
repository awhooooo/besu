package co.rsk.federate.adapter;

import static co.rsk.federate.testing.BitcoinFixture.REGTEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.rsk.federate.testing.BitcoinFixture;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.junit.jupiter.api.Test;

/**
 * The two bitcoinj forks cannot see each other's types, so everything crosses as bytes. A value
 * that changes on the way across would be a value the bridge is asked to act on that is not the one
 * the peer saw.
 */
class ThinConverterTest {

    private static final co.rsk.bitcoinj.core.NetworkParameters THIN_REGTEST =
        co.rsk.bitcoinj.core.NetworkParameters.fromID(co.rsk.bitcoinj.core.NetworkParameters.ID_REGTEST);

    @Test
    void aTransactionKeepsItsIdentityAcross() {
        Transaction original = BitcoinFixture.payTo(BitcoinFixture.someP2shAddress(5), Coin.COIN, 2);

        co.rsk.bitcoinj.core.BtcTransaction thin = ThinConverter.toThin(THIN_REGTEST, original);

        assertThat(thin.getHash().toString()).isEqualTo(original.getTxId().toString());
        assertThat(thin.bitcoinSerialize()).isEqualTo(original.bitcoinSerialize());
    }

    @Test
    void aSegwitTransactionKeepsBothOfItsIdentities() {
        Transaction original = BitcoinFixture.segwitPayTo(BitcoinFixture.someP2shAddress(5), Coin.COIN, 3);

        co.rsk.bitcoinj.core.BtcTransaction thin = ThinConverter.toThin(THIN_REGTEST, original);

        // getHash() is the txid; getHash(true) is the wtxid. The bridge keys on the first and
        // proves with the second, so conflating them would be silently wrong.
        assertThat(thin.getHash().toString()).isEqualTo(original.getTxId().toString());
        assertThat(thin.getHash(true).toString()).isEqualTo(original.getWTxId().toString());
        assertThat(original.getTxId()).isNotEqualTo(original.getWTxId());
    }

    @Test
    void anAddressKeepsItsBase58Across() {
        co.rsk.bitcoinj.core.Address thin =
            co.rsk.bitcoinj.core.Address.fromBase58(THIN_REGTEST, BitcoinFixture.someP2shAddress(11).toBase58());

        org.bitcoinj.core.Address original = ThinConverter.toOriginal(REGTEST, thin);

        // LegacyAddress prints its base58 form, which is the thing that must survive.
        assertThat(original.toString()).isEqualTo(thin.toBase58());
    }

    @Test
    void theNetworksLineUpByIdentifier() {
        assertThat(ThinConverter.toOriginal(THIN_REGTEST.getId()).getId()).isEqualTo(REGTEST.getId());
    }

    @Test
    void anUnknownNetworkIsRefusedRatherThanReturnedAsNull() {
        // NetworkParameters.fromID answers null, which would surface much later as an odd failure.
        assertThatThrownBy(() -> ThinConverter.toOriginal("org.bitcoin.nowhere"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("org.bitcoin.nowhere");
    }
}
