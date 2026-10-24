#!/usr/bin/env bash
# Session-start check of the MobiAI Graph index (.mobiai/graph/index.json), which is git-ignored and built per machine.
#
# Warns, through the hook's additionalContext and systemMessage, when the index is missing, when the checkout switched
# branch after it was built (re-index before trusting graph callers/context), or when commits landed after it (it may
# miss new symbols). Prints nothing when the index is current. The age comes from the files' modification times: git
# rewrites HEAD on a checkout and the branch's ref on a commit, and `mobiai graph init` rewrites the index. A branch
# whose ref sits only in packed-refs (or a reftable repository) skips the commit check until its next commit.
# Keep this file LF (`*.sh text eol=lf` in .gitattributes): bash fails on a CRLF checkout.
dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$dir" 2>/dev/null || exit 0
index=".mobiai/graph/index.json"

warn() {
  local text=${1//\\/\\\\}
  text=${text//\"/\\\"}
  printf '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"%s"},"systemMessage":"%s"}\n' \
    "$text" "$text"
}

if [ ! -f "$index" ]; then
  warn "MobiAI Graph index is missing. Run 'mobiai graph init' before using graph callers/context."
  exit 0
fi
command -v git >/dev/null 2>&1 || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0
head_file=$(git rev-parse --git-path HEAD 2>/dev/null)
ref=$(git symbolic-ref -q HEAD 2>/dev/null)
if [ -n "$ref" ]; then
  where="branch '${ref#refs/heads/}'"
  ref_file=$(git rev-parse --git-path "$ref" 2>/dev/null)
else
  # Detached: a commit rewrites HEAD itself, so it reads as a checkout; both mean re-index.
  where="a detached HEAD"
  ref_file=""
fi

if [ -f "$head_file" ] && [ "$head_file" -nt "$index" ]; then
  warn "MobiAI Graph index was built before the last checkout (now on $where). Run 'mobiai graph init' before using graph callers/context."
elif [ -n "$ref_file" ] && [ -f "$ref_file" ] && [ "$ref_file" -nt "$index" ]; then
  warn "MobiAI Graph index was built before the latest commit on $where. It may miss new symbols: run 'mobiai graph init' before relying on graph callers/context for them."
fi
exit 0
