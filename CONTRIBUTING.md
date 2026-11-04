# Contributing

Thank you for helping decentralize the Quantus Network.

## Ground rules
- This app is a **non-validator full node only**: no mining, no wallet features. PRs that add asset handling will be declined by design.
- Security first: no analytics/ads SDKs, no remote code loading, RPC stays on loopback.
- Be honest in benchmarks and tests — a passing build is not proof of correctness. Node changes must be verified against an upstream `quantus-node` (matching block hashes at the same height).

## Getting started
- Read `docs/DESIGN.md` first — it records the architecture, threat model, and what is still unverified.
- Look for issues labeled `good first issue`.
- For larger changes, open an issue to discuss the approach before writing code.

## Pull requests
- Keep PRs focused; describe how you tested (see the PR template).
- By contributing you agree your work is licensed under the project's license, Apache-2.0 (see `LICENSE`).
