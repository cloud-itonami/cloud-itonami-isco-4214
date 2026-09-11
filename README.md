# cloud-itonami-isco-4214

Open Occupation Blueprint for **ISCO-08 4214**: Debt Collectors and Related Workers.

This repository designs a forkable OSS business for an independent debt collection and recovery practice: a correspondence handling robot prepares and dispatches collection notices under a governor-gated actor, so the practice keeps its own collection records instead of renting a closed collections SaaS.

**Maturity: `:implemented`.** `src/debtcollection/` implements the
`DebtCollectionActor` as a `langgraph.graph/state-graph`
(`debtcollection.actor`) wired to a `Collection Advisor` (`debtcollection.advisor`)
and an independent `DebtCollectionGovernor` (`debtcollection.governor`),
following the itonami actor pattern (ADR-2607011000): `:intake -> :advise
-> :govern -> :decide -+-> :commit (:ok?) +-> :request-approval (:escalate?,
human-in-the-loop interrupt) +-> :hold (:hard?)`. 14 tests / 30 assertions
green (`kbb -M:test`). HARD invariants (always hold, never
overridable): client provenance, no-actuation (`:effect` must be
`:propose`), a registered account basis for any contact-attempt
proposal, the proposed contact hour falling inside the account's
registered permitted-contact window (contacting a debtor outside the
registered window is a harassment risk, not diligence), and no
harassment/threat language flag (harassment or threat language is
refused by construction, not merely discouraged). Always-escalate ops
(human sign-off regardless of confidence, mapping this repo's Trust
Controls in [`docs/business-model.md`](docs/business-model.md)):
`:approve-off-hours-contact` and `:approve-settlement-offer`.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a correspondence handling robot performs collection-notice printing, envelope stuffing and mailing-queue management under an actor that proposes
actions and an independent **Debt Collection Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
contact outside registered permitted-contact hours/channels) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
account referral + debtor contact preferences + collection policy
        |
        v
Collection Advisor -> Debt Collection Governor -> contact/settle, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `4214`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
