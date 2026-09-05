# 09: Create an Account

**What to build:** An operator can create an Account by naming a Currency and a starting
balance, and gets back the created Account. The Currency must be one of EUR, USD or HUF —
an Account in a Currency the Exchange Rate provider cannot quote is not creatable — and it
is fixed from this moment on.

Amounts cross the wire as an integer count of Minor Units in a field whose name says so,
so that no reader can mistake `10050` for a decimal quantity.

A rejected creation must say *which field* was wrong, using the field-level problem
extension from ticket 05, so the form can mark exactly what to fix rather than showing one
sentence.

**Blocked by:** 05, 08

**Status:** ready-for-agent

- [ ] Creating an Account returns `201` with the created Account
- [ ] An unknown Currency is rejected with a per-field problem document
- [ ] A negative starting balance is rejected with a per-field problem document
- [ ] The wire field for the amount is an integer Minor Unit count and named accordingly
- [ ] Web-layer tests cover the status codes and problem bodies without a database
