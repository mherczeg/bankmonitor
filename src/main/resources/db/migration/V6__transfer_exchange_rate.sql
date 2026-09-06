-- The Exchange Rate a cross-Currency Transfer was converted at, locked onto the Transfer
-- for its life so that the figure the operator was shown is the figure that settles.
-- Both columns are null for a same-Currency Transfer: no provider was called, and a rate
-- of one written here would claim a quote that was never fetched.
--
-- One `alter table` per column, because H2 takes no comma-separated list of `add column`
-- clauses and fails the whole migration on the first comma. The README beside this file has
-- it with the other differences worth knowing about.
alter table transfers
    add column exchange_rate numeric(20, 10);

alter table transfers
    add column exchange_rate_fetched_at timestamp(6) with time zone;

-- The rate, its timestamp and the two Currencies are one fact in four columns, so the
-- three ways they can disagree are refused here as well as in the entity that writes them:
-- a rate on a same-Currency Transfer, a cross-Currency Transfer with no rate, and a rate
-- that does not say when it was quoted.
--
-- Written as two compound branches rather than an `or` chain of equalities on one column,
-- which is the shape the README beside this file warns folds into an `in` list and fails
-- every later insert. `TransferRoundTripsInEveryStatusTest` holds all three branches by
-- refusing a bad row rather than by admitting a good one: `refusesACrossCurrencyTransferWithNoRate`,
-- `refusesASameCurrencyTransferCarryingARate` and `refusesARateWithNoFetchTimestamp`.
alter table transfers
    add constraint transfers_rate_iff_cross_currency
        check ((debited_amount_currency = credited_amount_currency
                    and exchange_rate is null
                    and exchange_rate_fetched_at is null)
            or (debited_amount_currency <> credited_amount_currency
                    and exchange_rate is not null
                    and exchange_rate_fetched_at is not null
                    and exchange_rate > 0));
