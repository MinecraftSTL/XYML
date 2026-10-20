#!/usr/bin/env bash

set -euo pipefail

source_branch="${1:-}"
target_branch="${2:-}"
base_commit=""
source_commit=""
merge_commit=""

if [[ -z "$source_branch" || -z "$target_branch" ]]; then
    echo "Usage: validate-branch-flow.sh <source-branch> <target-branch> [merge-commit]" >&2
    echo "   or: validate-branch-flow.sh <source-branch> <target-branch> <base-commit> <source-commit> [merge-commit]" >&2
    exit 2
fi

case "$#" in
    2)
        ;;
    3)
        merge_commit="$3"
        ;;
    4)
        base_commit="$3"
        source_commit="$4"
        ;;
    5)
        base_commit="$3"
        source_commit="$4"
        merge_commit="$5"
        ;;
    *)
        echo "Invalid argument count: $#" >&2
        exit 2
        ;;
esac

if (( $# == 3 )) && [[ -z "$merge_commit" ]]; then
    echo "Merge commit must not be empty" >&2
    exit 2
fi
if (( $# >= 4 )) && [[ -z "$base_commit" || -z "$source_commit" ]]; then
    echo "Base and source commits must not be empty" >&2
    exit 2
fi
if (( $# == 5 )) && [[ -z "$merge_commit" ]]; then
    echo "Merge commit must not be empty" >&2
    exit 2
fi

stable_version() {
    local commit="$1"
    local version
    version="$(
        git show "${commit}:config/project.properties" \
            | awk -F= '$1 == "stableVersion" { print $2; exit }'
    )"
    if [[ -z "$version" ]]; then
        echo "Missing stableVersion at $commit" >&2
        return 1
    fi
    printf '%s\n' "$version"
}

release_branch_ref() {
    local branch="$1"
    local ref
    for ref in "refs/heads/$branch" "refs/remotes/origin/$branch"; do
        if git rev-parse --verify --quiet "${ref}^{commit}" >/dev/null; then
            printf '%s\n' "$ref"
            return 0
        fi
    done
    echo "Missing release branch ref: $branch" >&2
    return 1
}

validate_baseline_chain() {
    local channel="$1"
    local commit="$2"
    local first_parent_version
    local merge_version
    local release_ref
    local second_parent_version
    local -a parents

    while true; do
        release_ref="$(release_branch_ref "$channel")"
        if ! git rev-list --first-parent "$release_ref" | grep -Fx "$commit" >/dev/null; then
            echo "Stable baseline carrier is not on the $channel first-parent history: $commit" >&2
            return 1
        fi

        read -r -a parents <<< "$(git rev-list --parents -n 1 "$commit")"
        if (( ${#parents[@]} != 3 )); then
            echo "Stable baseline carrier on $channel must be a two-parent merge: $commit" >&2
            return 1
        fi

        first_parent_version="$(stable_version "${parents[1]}")"
        merge_version="$(stable_version "$commit")"
        second_parent_version="$(stable_version "${parents[2]}")"
        if [[ "$merge_version" != "$second_parent_version" ]]; then
            echo "Stable baseline carrier on $channel must take stableVersion from its second parent: $commit" >&2
            return 1
        fi

        if [[ "$channel" == "main" ]]; then
            if [[ "$merge_version" == "$first_parent_version" ]]; then
                echo "Stable release merge on main must change stableVersion: $commit" >&2
                return 1
            fi
            return 0
        fi
        commit="${parents[2]}"
        case "$channel" in
            beta)
                channel="main"
                ;;
            alpha)
                channel="beta"
                ;;
            dev)
                channel="alpha"
                ;;
            *)
                echo "Cannot trace a Stable baseline from channel: $channel" >&2
                return 1
                ;;
        esac
    done
}

case "$source_branch:$target_branch" in
    dev:alpha|alpha:beta|beta:main)
        flow="promotion"
        ;;
    hotfix/*:main)
        flow="promotion"
        ;;
    main:beta|beta:alpha|alpha:dev)
        flow="sync"
        ;;
    *:dev)
        case "$source_branch" in
            main|beta|dev)
                echo "Release branches must synchronize one adjacent channel at a time: $source_branch -> $target_branch" >&2
                exit 1
                ;;
            *)
                flow="development"
                ;;
        esac
        ;;
    *)
        echo "Unsupported release branch flow: $source_branch -> $target_branch" >&2
        exit 1
        ;;
esac

source_repository="${SOURCE_REPOSITORY:-}"
target_repository="${TARGET_REPOSITORY:-}"
if [[ "$flow" != "development"
    && -n "$target_repository"
    && "$source_repository" != "$target_repository" ]]; then
    echo "Release branches must come from the protected repository: $source_repository" >&2
    exit 1
fi

if [[ -n "$merge_commit" && "$flow" != "development" ]]; then
    read -r -a commit_and_parents <<< "$(git rev-list --parents -n 1 "$merge_commit")"
    if (( ${#commit_and_parents[@]} != 3 )); then
        echo "Release flow $source_branch -> $target_branch must use a two-parent --no-ff merge: $merge_commit" >&2
        exit 1
    fi
    if [[ -z "$base_commit" ]]; then
        base_commit="${commit_and_parents[1]}"
        source_commit="${commit_and_parents[2]}"
    elif [[ "$base_commit" != "${commit_and_parents[1]}"
        || "$source_commit" != "${commit_and_parents[2]}" ]]; then
        echo "Release merge parents do not match the reviewed base and source commits: $merge_commit" >&2
        exit 1
    fi
fi

if [[ -n "$base_commit" && "$flow" != "development" ]]; then
    base_stable_version="$(stable_version "$base_commit")"
    source_stable_version="$(stable_version "$source_commit")"
    if [[ "$target_branch" == "main" ]]; then
        if [[ "$base_stable_version" == "$source_stable_version" ]]; then
            echo "Release flow $source_branch -> $target_branch must carry a changed Stable baseline" >&2
            exit 1
        fi
    elif [[ "$flow" == "promotion" && "$base_stable_version" != "$source_stable_version" ]]; then
        echo "Promotion $source_branch -> $target_branch must not change the Stable baseline" >&2
        exit 1
    fi

    if [[ "$flow" == "sync" ]]; then
        validate_baseline_chain "$source_branch" "$source_commit"
    fi

    if [[ -n "$merge_commit" ]]; then
        merged_stable_version="$(stable_version "$merge_commit")"
        if [[ "$merged_stable_version" != "$source_stable_version" ]]; then
            echo "Release merge must take stableVersion from its second parent: $merge_commit" >&2
            exit 1
        fi
    fi
fi

echo "$flow"
