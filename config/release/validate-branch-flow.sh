#!/usr/bin/env bash

set -euo pipefail

source_branch="${1:-}"
target_branch="${2:-}"
merge_commit="${3:-}"

if [[ -z "$source_branch" || -z "$target_branch" || $# -gt 3 ]]; then
    echo "Usage: validate-branch-flow.sh <source-branch> <target-branch> [merge-commit]" >&2
    exit 2
fi

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

if [[ -n "$merge_commit" && "$flow" == "promotion" ]]; then
    read -r -a commit_and_parents <<< "$(git rev-list --parents -n 1 "$merge_commit")"
    if (( ${#commit_and_parents[@]} < 3 )); then
        echo "Promotion $source_branch -> $target_branch must use a --no-ff merge commit: $merge_commit" >&2
        exit 1
    fi
fi

echo "$flow"
