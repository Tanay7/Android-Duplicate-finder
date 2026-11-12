#!/usr/bin/env bash
# Commit build outputs (APK, screenshots) to the branch that was built, safely:
#   publish.sh "<commit message>" <source> <destination> [<source> <destination> ...]
# The files are committed on top of the branch's latest tip. If newer code has been pushed since this build
# started, nothing is published: the newer build will publish its own outputs.
set -eu
msg=$1; shift
stage=$(mktemp -d)
pairs=()
while [ $# -ge 2 ]; do cp "$1" "$stage/$(basename "$2")"; pairs+=("$2"); shift 2; done
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
for attempt in 1 2 3 4 5; do
  git fetch -q origin "$GITHUB_REF_NAME"
  # docs-only pushes start no build (paths-ignore), so they must not stop this one from publishing
  if ! git diff --quiet "$GITHUB_SHA" FETCH_HEAD -- . ':(exclude)release' ':(exclude)docs' ':(exclude)*.md'; then
    echo "Newer code is on $GITHUB_REF_NAME; its own build will publish. Skipping."; exit 0
  fi
  git checkout -q -B publish-outputs FETCH_HEAD
  for dst in "${pairs[@]}"; do mkdir -p "$(dirname "$dst")"; cp "$stage/$(basename "$dst")" "$dst"; git add "$dst"; done
  git commit -q -m "$msg" || { echo "Nothing changed."; exit 0; }
  if git push -q origin "HEAD:$GITHUB_REF_NAME"; then echo "Published: ${pairs[*]}"; exit 0; fi
  echo "Push attempt $attempt was rejected; retrying"; sleep $((attempt * 3))
done
echo "Could not publish after 5 attempts"; exit 1
