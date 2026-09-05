/**
 * The vocabulary every slice shares: money and its currencies, and the problem types the
 * API answers with.
 *
 * <p>Depended on by everything and depending on nothing, which is what keeps it from
 * becoming the cycle in the middle of the graph. Only genuinely context-wide concepts
 * belong here — anything owned by one slice lives in that slice.
 */
package hu.bankmonitor.payments.common;
