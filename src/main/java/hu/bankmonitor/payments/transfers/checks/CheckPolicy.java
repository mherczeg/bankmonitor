package hu.bankmonitor.payments.transfers.checks;

import hu.bankmonitor.payments.transfers.Transfer;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which Checks a Transfer has to pass before it can settle.
 *
 * <p>The one place that answer is given. Adding a condition to this payment gateway is a
 * constant on {@link Check}, a line here and a row in the ledger — no new endpoint, no new
 * state, and nothing in the settlement rule to revisit. That extensibility is the whole
 * reason ADR-0001 chose an asynchronous lifecycle over settling inside the request.
 */
@Component
class CheckPolicy {

	/**
	 * Every Transfer requires both Checks today, so the Transfer goes unread — and the
	 * parameter is still the point. A policy that cannot see the Transfer cannot grow the
	 * first rule that depends on one ("manual approval above ten thousand", "fraud
	 * screening only across Currencies"), and this ticket is the one that gets to choose
	 * the signature while it has no other caller to break.
	 *
	 * <p>Deliberately not {@code EnumSet.allOf(Check.class)}, which would be shorter and
	 * would silently apply the first conditional Check to every Transfer ever written. What
	 * Checks <em>exist</em> and what a given Transfer <em>requires</em> are two questions,
	 * and this class only answers the second.
	 */
	Set<Check> requiredFor(Transfer transfer) {
		return EnumSet.of(Check.FRAUD, Check.MANUAL_APPROVAL);
	}
}
