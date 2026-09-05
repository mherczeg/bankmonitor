# 33: Money formatting and parsing, as a plain module

**What to build:** The one place in the frontend that knows a Currency's decimals. It
converts a Minor Unit count into the familiar decimal form for display (2 places for EUR
and USD, 0 for HUF) and parses operator input back into Minor Units.

**Per-Currency decimals live only at the edges**, and this module is that edge. Nothing
else in the frontend divides or multiplies by a hundred.

Pure TypeScript, no React. This is the rule the whole frontend test strategy rests on:
extract the logic, unit test it, and write as few component tests as possible — integration
confidence comes from a real browser, not from a simulated DOM.

**Blocked by:** 31

**Status:** ready-for-agent

- [ ] Formatting renders a Minor Unit count with the right number of decimals per Currency
- [ ] Parsing rejects more decimal places than the Currency allows
- [ ] Parsing rejects non-numeric input, negatives and zero
- [ ] Round-tripping format → parse is lossless for every supported Currency
- [ ] The module imports nothing from React
