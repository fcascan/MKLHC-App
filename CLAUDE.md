# Working with the author on MKLHC-App

Instructions for every agent session in this repository. They come from how the author works; follow them exactly.

## Commits: the author commits, the agent hands over the message

- The author runs `git add -A`, commits and pushes himself, at every gate. The agent never commits or pushes, and
  subagents never run git at all, not even read-only commands.
- **Every time there are uncommitted changes when the agent stops, its final reply contains the complete commit
  message in a fenced block, ready to paste.** No exception: not "after the review", not "in the next turn".
- **Where it goes:** in the final reply text, the last thing the author reads. When a change is pending, the turn
  ends WITHOUT a next-step question box (in the desktop app, `wrap_up` with only a recap, no `ask` and no buttons):
  a question box ends the turn and hides everything written around it, so a message written before it is lost. The
  next-step question goes in plain text after the commit message.
- Before ending any turn, check: are there uncommitted changes (`git status --short`)? If yes, is the full message in
  the final reply? If not, write it.
- Format: `[tag] subject` (tags: feat, fix, docs, build, chore), a lead sentence, then bullets with the changes;
  name the origin of any ported or adapted code. No `Co-Authored-By` trailer and no mention of any AI or tool.
- Say which files the commit covers and whether any are new (untracked), so `git add -A` picks them up.

## Other standing rules

- Never name the kart's manufacturer, console or game anywhere (code, docs, commits, UI); say "the kart".
- Never log or print seeds, PSKs, keys, the master secret, tokens or device identities. Never paste a pairing
  payload into an online service.
- Real captures (`spike-captures/`, `*.viddump`, `*.pcap`) hold decrypted traffic or the camera picture: they stay
  local and never go into the repository or a test.
- Work mechanic: test first, one model per subagent, a two-stage review (spec, then code quality), then a gate with
  forced reruns (`.\gradlew.bat <module>:desktopTest --rerun ... desktopTest testDebugUnitTest --continue`).
- On hardware: stop the desktop app by closing its window, never with Ctrl+C under Gradle (it leaves the adapter
  beaconing until unplugged); confirm the beacon is off after every run.
- Replies to the author are in Spanish; code, comments, commit messages and repository docs are in English. Longer
  guides for the author go in the git-ignored `.ia-docs/`.
- The plan and its backlog: `.ia-docs/plans/2026-10-14-mklhc-app-implementation-plan.md`; project memory:
  `.mobiai/brain/memories/`.
