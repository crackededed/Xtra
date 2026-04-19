# Release Process

## Release model

Public releases should be intentional.

Recommended flow:
1. finish release PRs
2. merge to `master`
3. update `CHANGELOG.md`
4. add `docs/release-notes/X.Y.Z.md`
5. create tag `vX.Y.Z`
6. let release workflow publish the APK and GitHub Release

## Before release
- version bump committed
- release notes written
- screenshots updated if release is visual
- README updated if user-facing behavior changed
- build and tests pass
- critical manual checks completed

## Release note contents
Each release note should include:
- highlights
- player changes
- UI / visual changes
- fixes
- known issues

## After release
- verify the GitHub release page
- verify APK artifact name
- verify screenshots / site update if included
- delete merged release branches
