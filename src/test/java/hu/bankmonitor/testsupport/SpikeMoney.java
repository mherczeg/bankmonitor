package hu.bankmonitor.testsupport;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * The shape design decision 16 wants for Money, as a record: a {@code long} count of
 * minor units paired with the currency it is denominated in.
 *
 * <p>This is the subject of the first of the two ecosystem bets ticket 01 exists to
 * settle. A record has no no-arg constructor and no setters, so mapping one as an
 * {@code @Embeddable} requires Hibernate to instantiate it through its canonical
 * constructor. Hibernate has done that since 6.2 and Boot 4 ships Hibernate 7, but if
 * the support were absent every entity in the domain would need a different shape --
 * hence proving it here rather than trusting a changelog.
 *
 * <p>The {@code @Enumerated} annotation is a second, smaller bet riding along: record
 * components propagate their annotations to the generated field only when the
 * annotation's {@code @Target} permits it. {@code @Enumerated} targets fields, so it
 * should reach the mapping and store {@code "EUR"} rather than the ordinal {@code 0}.
 */
@Embeddable
public record SpikeMoney(long minorUnits, @Enumerated(EnumType.STRING) SpikeCurrency currency) {
}
