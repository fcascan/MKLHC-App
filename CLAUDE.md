# CLAUDE.md

How to work in this repository is in AGENTS.md, for every assistant. Read it and follow it exactly:

@AGENTS.md

## Claude Code specifics

- **Nothing but the plain-text next-step question comes after the commit message.** When a change is pending, the turn
  ends WITHOUT a
  next-step question box (in the desktop app, `wrap_up` with only a recap, no `ask` and no buttons): a question box
  ends the turn and hides everything written around it, so a message written before it is lost. The commit message
  goes in the final reply (AGENTS.md section 7), and the next-step question in plain text after it.
- **Save to the Brain without asking.** At the end of a task, run the matching `mobiai brain save` yourself instead of
  proposing it; the confirmation MobiAI's own skills pause for is standing here. Report the entry ids and that
  `.mobiai/brain/` now has changes to commit.
- **Invoke MobiAI capabilities with the `Skill` tool** (`mobiai-mobile-debugging`, `mobiai-mobile-tdd`,
  `mobiai-mobile-verification`, ...), not by reading their `SKILL.md`. Where one of them would commit, push or open a
  PR, AGENTS.md section 2 wins: the author does that.
- **Subagents** skip the Brain save and the Graph pre-flight: the parent owns both for the whole task, and repeating
  them only produces duplicate entries. Every brief repeats the rules that bind the subagent (Windows shells, files
  through Write/Edit, no `cd` prefix, no sleep), forbids git outright, the hardware, `update_plan` and `wrap_up`, and
  gives the exact scoped test command. One model per subagent; reviews run in two stages, the spec first, then the code
  quality.
- **Hardware runs are launched by the author from Android Studio or a normal PowerShell, never from the Claude Terminal
  panel.** The Claude desktop app is an MSIX package, so a process it starts sees a virtualized `%APPDATA%` and reads
  another pairing identity than the one the kart is paired with.
- A `SessionStart` hook (`.claude/settings.json`) runs `.claude/hooks/graph-freshness-check.sh`, which warns when the
  Graph index is missing, older than the last branch switch, or older than the latest commit. On the first two, run
  `mobiai graph init` (it writes only inside `.mobiai/graph/`) and mention that you did.
