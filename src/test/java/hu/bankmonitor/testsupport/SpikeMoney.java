package hu.bankmonitor.testsupport;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * The shape design decision 16 wants for Money, as a record: a {@code long} count of
 * minor units paired with the currency it is denominated in.
 *
 * <p>Subject of the first of ticket 01's two ecosystem bets. The {@code @Enumerated}
 * annotation is a smaller bet riding along: a record component propagates its annotations
 * to the generated field only when the annotation's {@code @Target} permits it.
 */
@Embeddable
public record SpikeMoney(long minorUnits, @Enumerated(EnumType.STRING) SpikeCurrency currency) {
}
