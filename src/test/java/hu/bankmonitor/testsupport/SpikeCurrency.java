package hu.bankmonitor.testsupport;

/**
 * Stand-in for the real {@code Currency}, which ticket 06 puts in {@code common}.
 *
 * <p>This spike deliberately does not use the production type: the point is to prove the
 * mapping works <em>before</em> the domain is written against the assumption, so the
 * spike cannot depend on the thing it is meant to de-risk.
 */
public enum SpikeCurrency {
	EUR,
	USD,
	HUF
}
