# Repository workflow

- Commit completed work at sensible, frequent checkpoints without waiting for an explicit request.
- Push every commit immediately to the current remote branch after creating it.
- Prefer several focused commits over one large accumulated working tree.
- Use judgment when grouping tightly coupled changes; keep each commit coherent and verified in proportion to its risk.
- Before committing, exclude local artifacts, device captures, secrets, generated files, and unrelated user changes.
- Keep the working tree clean after finishing a task whenever it is safe to do so.

# GitHub issues

- Track larger features, architectural work, investigations, and deferred follow-ups in GitHub issues.
- Before creating an issue, search the open and closed issues and update a matching issue instead of creating a duplicate.
- Keep small, immediately implemented fixes out of the issue tracker unless they reveal meaningful follow-up work.
- Give issues a concrete goal, relevant context, scoped implementation notes or non-goals, and verifiable acceptance criteria.
- Keep issue state and descriptions current as implementation changes the starting point; reference or close the issue from the corresponding commit or pull request when appropriate.
