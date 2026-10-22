package javatesa6.ledgersettlement;

/** How many payments a merchant has and their gross total in minor units, computed by the database. */
public record MerchantTotals(Long paymentCount, Long grossMinor) {
}
