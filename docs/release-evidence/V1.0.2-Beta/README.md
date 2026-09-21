# V1.0.2-Beta SDK engineering evidence

The JSON files in this directory were generated from the paired AVC_HEVC AARs
in `app/libs` by the SDK repository's `tools/compliance/audit_release.py` and
`tools/compliance/generate_sbom.py` on 2026-09-21. Both artifacts passed the
profile and pair audit. The transport's three arm64 native libraries also
passed the 16 KB ELF `LOAD` alignment check.

| Artifact | SHA-256 |
| --- | --- |
| Core | `2e6dbdff2882c83e28114cee65c8cff7c496d94ae665cf2105bde858de0a3c88` |
| Transport | `e1a62777449c136ddeb6de2f7322a563df93409a9eb22c27428f88b926309b4e` |

These engineering reports are not the commercial approval record. Codec
rights, LGPL distribution, source/relinking materials, store policy, production
license, app signing, and device qualification remain open as detailed in
`docs/STORE_RELEASE_RISK_REVIEW.md`.
