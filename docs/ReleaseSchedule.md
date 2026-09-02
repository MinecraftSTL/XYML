# xOyz Minecraft Launcher Release Model

<!-- #BEGIN LANGUAGE_SWITCHER -->
中文 ([简体](ReleaseSchedule_zh.md), [繁體](ReleaseSchedule_zh_Hant.md)) | **English**
<!-- #END LANGUAGE_SWITCHER -->

This document defines the XYML release model beginning with `1.0.0`.

## Scope and History

- `1.0.0` is the first stable version under this model.
- Historical development snapshots, tags, and changelogs keep the release model and version meaning they originally used. They are not renamed or reinterpreted.
- The unreleased `3.17.0` stable test artifact has one deliberately narrow migration exception: it may recognize `1.0.0` stable as an update. This is not a general version epoch or compatibility adapter, and no other `3.x -> 1.x` transition is implied.
- Every version component is written in decimal. Hexadecimal, Base64, and other radices are not used.

## Version Format

The number of decimal components identifies the release channel.

| Channel | Format | Example | Audience |
| --- | --- | --- | --- |
| Stable | `x.y.z` | `1.0.0` | General users |
| Beta | `x.y.z.b` | `1.0.0.1` | Unselected volunteers |
| Alpha | `x.y.z.b.a` | `1.0.0.0.1` | Selected testers |
| Dev | `x.y.z.b.a.d` | `1.0.0.0.0.1` | Developers and early verification |

The first three components describe the scale of a stable change:

- `x` changes for a large architectural rewrite.
- `x.y` changes for a feature release.
- `x.y.z` changes for bug fixes and small adjustments.

The additional `b`, `a`, and `d` counters identify beta, alpha, and dev candidates based on that stable line. Each promotion chooses a new version for the target channel; a version is never promoted by merely truncating its trailing components.

### Ordering and Promotion

Decimal comparison remains chronological when target counters are advanced correctly. A normal patch candidate can progress as follows:

```text
1.0.0 < 1.0.0.0.0.1 < 1.0.0.0.1 < 1.0.0.1 < 1.0.1
stable     dev             alpha          beta        stable
```

For example, beta `3.17.0.1` normally lands in stable `3.17.1`, not stable `3.17.0`. If an emergency fix advances stable to `3.17.1` first, the candidate may first appear in stable `3.17.2`. The stable version is selected when the beta is actually promoted, based on both its changes and the then-current stable version.

A patch that promotes Beta to `main` or publishes a Stable hotfix must update `stableVersion` in
`config/project.properties` to the selected stable version. The subsequent `main -> beta -> alpha -> dev`
synchronization must carry that stable baseline to every release branch.

## Branch Model

| Branch | Channel | Role |
| --- | --- | --- |
| `main` | Stable | Generally available releases and emergency fixes |
| `beta` | Beta | Public testing by unselected volunteers |
| `alpha` | Alpha | Testing by a selected group |
| `dev` | Dev | Default branch for feature and fix integration |

GitHub's default branch should be `dev`. Feature and fix branches start from `dev` and merge back into `dev` after
their focused tests pass. A feature or fix branch is never a release-channel source.

```mermaid
flowchart LR
    F["Feature or fix branch"] --> D["dev"]
    D -->|"--no-ff promotion"| A["alpha"]
    A -->|"--no-ff promotion"| B["beta"]
    B -->|"--no-ff promotion"| S["main / stable"]
    H["hotfix/*"] -->|"--no-ff promotion"| S
    S -. "Stable baseline sync" .-> B
    B -. "Stable baseline sync" .-> A
    A -. "Stable baseline sync" .-> D
```

Every merge toward a more stable channel must use `git merge --no-ff`, including `hotfix/* -> main`. Release branches
may be synchronized toward a less stable channel only when a Stable promotion or hotfix has changed the Stable
baseline, and only one adjacent channel at a time: `main -> beta -> alpha -> dev`. Ordinary `alpha -> dev` or
`beta -> alpha` synchronization is not a release boundary and is not part of the normal workflow. Each synchronization
must directly use the preceding baseline carrier: the Stable release or hotfix merge on `main`, then each preceding
synchronization merge. Unrelated commits cannot be inserted into that reverse chain. Do not rebase or force-push
shared release branches.

The release-policy workflow validates the exact base and source commits before merge, including the complete Stable
baseline chain, and audits the resulting release merge afterward. The post-merge audit requires the merge result to
take `stableVersion` from its second parent. Repository rules must also allow merge commits for release PRs; the
post-merge audit still detects squash or rebase merges that bypass that requirement.

## Distribution and Feedback

Update frequency increases from Stable to Beta, Alpha, and Dev.

| Channel | Github Release | Official website | Feedback entry |
| --- | --- | --- | --- |
| Stable | Published | Published | Public |
| Beta | Not published | Published | Public |
| Alpha | Not published | Not published | Restricted testing program |
| Dev | Not published | Not published | Restricted testing program |

Only Stable artifacts are published through Github Release. The official website publishes Stable and Beta artifacts.
Alpha and Dev artifacts are not distributed publicly, and reports for those channels are accepted only through the
restricted testing program. The public bug form is reserved for current Stable and Beta releases.

## Building and Publishing

The build accepts these release inputs:

- `RELEASE_CHANNEL`: exactly `stable`, `beta`, `alpha`, or `dev`.
- `RELEASE_VERSION`: an explicit complete decimal version used for a promotion.
- `BUILD_NUMBER`: the final positive decimal component used for an ordinary CI build when `RELEASE_VERSION` is absent.
- `STABLE_VERSION`: an optional override of `stableVersion` in `config/project.properties`.

The root Gradle tasks in the `stl` group infer versions from Git topology. Beta, Alpha, and Dev form hierarchical
epochs. A promotion snapshots the source channel's complete prefix and clears the target channel and every less stable
counter. A new Stable baseline is carried by the explicit adjacent sync chain `main -> beta -> alpha -> dev`.
Therefore, a new Beta `x.y.z.b` makes Alpha `x.y.z.b.0` and Dev `x.y.z.b.0.0`; a new Alpha `x.y.z.b.a` makes Dev
`x.y.z.b.a.0`. A less stable branch does not need a reverse merge from the promoted channel: its first commit after
the promotion inherits the new prefix. `buildMain`, `buildBeta`, `buildAlpha`, and `buildDev` inject the inferred
version into their isolated builds. Histories without an identifiable promotion boundary retain the legacy merge-base
calculation and are not renumbered.

For a selective Dev promotion, suppose A is `1.0.0.0.0.0` and the following B is `1.0.0.0.0.1`. Merge A into Alpha,
producing `1.0.0.0.1`. B remains `1.0.0.0.0.1`; the first subsequent Dev commit C, made after that Alpha promotion,
starts the new epoch at `1.0.0.0.1.0`.

Feature and detached builds keep the six-component Dev shape `x.y.z.0.0.d`. Their `d` is the first-parent distance
from the Alpha merge base to the newest reachable commit on `dev`'s first-parent history. This keeps the third Dev
commit at `.3`; commits made only on the feature branch and uncommitted changes do not advance it. Other official build
invocations still reject missing or malformed release inputs.

The Github Release publishing workflow runs only from `main`. It creates a Stable release and updates only the Stable
channel descriptor; it does not publish Beta, Alpha, or Dev releases. Official-website distribution follows the table
above.
