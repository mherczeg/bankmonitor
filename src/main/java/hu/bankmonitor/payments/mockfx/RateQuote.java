package hu.bankmonitor.payments.mockfx;

import java.math.BigDecimal;

/**
 * What the stand-in provider answers a rate request with.
 *
 * <p>It echoes the pair back rather than returning a bare number, which is what a real
 * rate service does and what keeps a response readable on its own — a client that logs
 * one has logged what it asked for.
 *
 * @param base  the currency one unit of which the rate prices
 * @param quote the currency the rate is expressed in
 * @param rate  units of {@code quote} per one unit of {@code base}, in major units — a
 *              decimal, and the one number in this system that is not money
 */
record RateQuote(String base, String quote, BigDecimal rate) {
}
