/**
 * Accounts and the balances they hold: the {@code Account} entity, its HTTP endpoints,
 * and the Reserved Amount that a pending Transfer holds against its Available Balance.
 *
 * <p>Exports no port. {@code transfers} reaches accounts through ordinary types in this
 * package; the repository behind them stays package-private, so nothing outside can
 * query or write a balance without going through the operations declared here.
 */
package hu.bankmonitor.payments.accounts;
