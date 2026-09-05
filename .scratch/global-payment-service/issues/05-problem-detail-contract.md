# 05: One error format, with the type URN as the sole discriminator

**What to build:** Every error this API can emit is an RFC 9457 problem document, and a
client branches on exactly one field: the `type` URN. Two fields that can disagree is
the failure mode this exists to prevent.

Use the framework's own problem-document support rather than a hand-rolled envelope. The
framework already emits problem documents for validation rejections, unsupported media
types, wrong methods and malformed JSON — a custom shape would be a *second* error
format, not a replacement.

Two additions on top of the default:

- **Field-level validation errors as an extension member.** The default packs every
  violation into one unusable sentence, which no form can mark up.
- **The `type` URNs as constants in `common`**, so the frontend's generated types and the
  backend agree on the vocabulary from one place.

`Retry-After` policy is part of this contract: present where retrying will help, absent
where it will not. Later tickets rely on that distinction being machine-readable.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] A validation failure returns a problem document with per-field detail, not one
      sentence
- [ ] Malformed JSON, an unsupported media type and a wrong method all return the same
      document shape
- [ ] Problem-type URNs live as constants in one place
- [ ] Tests cover the shape at the web layer without starting a database
