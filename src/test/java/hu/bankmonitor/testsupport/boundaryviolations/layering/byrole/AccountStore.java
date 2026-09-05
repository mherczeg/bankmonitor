package hu.bankmonitor.testsupport.boundaryviolations.layering.byrole;

import org.springframework.data.repository.Repository;

/** A repository by role rather than by name. See {@link AccountEndpoint}. */
public interface AccountStore extends Repository<Object, Long> {
}
