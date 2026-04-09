# Verifier Endpoint Repo Guidance

- Use GPT-5.4 by default for protocol-facing work in this repo.
- Treat `project-docs/docs/EIDAS_ARF_Implementation_Brief.md` and `project-docs/docs/AI_Working_Agreement.md` as mandatory constraints.
- This repo owns verifier request generation, request object handling, trust behaviour, and backend presentation processing.
- The current program priority is verifier delivery, so protocol correctness here matters more than UI convenience.
- When verifier protocol behaviour, request-object handling, trust validation, or local verifier runtime changes, update `project-docs` in the same task.
- Default Git flow in this workspace is local `wip/<stream>` commits promoted directly with `git push origin HEAD:main`; do not publish remote `wip/<stream>` branches unless explicitly requested.

## Local Checks

- `./gradlew test`

## Sensitive Areas

- Do not casually alter OpenID4VP request semantics, `request_uri` handling, verifier trust assumptions, or validation behaviour.
- Keep local-only verifier cert bundles and generated runtime certs out of version control.