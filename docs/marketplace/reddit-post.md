**Title:** I built Upstack, a JetBrains plugin for GitHub stacked pull requests: the whole stack in a tool window, and every button runs the `gh stack` command you'd type (free, no account)

---

Hey r/Jetbrains 👋

I've been building a plugin called **Upstack**, and it's finally at a point where I'd love other people to try it and tell me what's wrong with it.

It's for **stacked pull requests**: instead of one huge PR, you split the work into small layers, each one a branch with its own PR on top of the one below. GitHub now has this built in with the [`gh stack`](https://github.com/github/gh-stack) extension, but juggling five branches from the terminal gets old fast: which layer am I on, which ones need a rebase, which PR is approved, why did the rebase stop…

So Upstack is a tool window that shows the whole stack and lets you work on it from the IDE. It's a visual front end for `gh stack` and `gh pr`: **every button runs the same command you would type**, and the tooltip tells you which one. You can switch between the IDE and the terminal at any point. No account, no server, no tokens: it uses your own `gh` and its login.

Here's everything it does. It's a long list, sorry 😅

[📷 01-stacked-prs-in-your-ide.png]

## The stack at a glance

- **The stack as a graph**, from the top layer down to the trunk. Per layer: branch, PR number, draft or ready, title, size (`+120 −30`), labels, review decision and CI.
- **What needs attention is marked**: the layer you're on (*HEAD*), layers that **need a rebase**, a PR whose base on GitHub isn't the layer below (**Wrong base**), and PRs GitHub can't merge as they are (**Conflicts**, **Behind**).
- **Hover a layer** for the whole story: its position in the stack, review, CI, files changed, labels and the PR link.
- One-line summary on top: `5 layers on main · 1 draft · 1 not published`.
- All the PR data comes in **one GraphQL call** for the whole stack, and it refreshes when git actually changes (branch, commits, refs), not on a timer.

[📷 02-every-layer-at-a-glance.png]

## Starting a stack

- **New Stack…**: type the layer names and see the resulting stack as a graph before anything runs. The base can be **any branch**, local or remote only, even a layer of another stack.
- **Add Layer…** on top, optionally committing your current changes in the new layer (all changes, tracked files only, or just what's staged).
- Every dialog shows the exact `$ gh stack …` line it's going to run, live, as you type.

[📷 03-start-a-stack.png]

## Publishing

- **Publish Stack…**: layer by layer, choose which PRs end up **ready for review** and which stay **drafts**, and give the new ones a title and a description.
- Or in one click: **as drafts** (`gh stack submit --auto`) or **ready for review** (`--open`, after showing you which PRs will change, because `--open` also flips existing drafts).
- **Publish a single layer** from its own menu.
- From each layer: **Mark Ready / Convert to Draft**, **Edit Title…**, **Labels…** (with the repo's labels and colours), and a **final-layer label** (`stack-final` by default, configurable, only one per stack).

[📷 04-publish-your-way.png]

## Rebasing only what changed

This is the part I use the most.

- You fix something in a lower layer. **Rebase Upstack** (`gh stack rebase --upstack --no-trunk`) carries it to the layers above **without fetching or rebasing onto main**, so the only conflicts are the ones your change causes, not everything that landed on main this week.
- It's offered **right after you commit in the IDE** on a layer that isn't the top (or it just does it every time, your choice), from the *needs rebase* banner, and from each layer's menu.
- The banner knows the difference between "a layer is behind the one below" (rebase upstack) and "the bottom layer is behind main" (rebase the whole stack).
- The toolbar has the other rebases too: whole stack, downstack, and layers onto each other.
- After a rebase, a banner offers **Push Stack** (`gh stack push`) until the branches are pushed, from the IDE or from the terminal.
- The first rebase asks whether to turn on **`git rerere`**, like `gh stack` does in the terminal, and stores the answer in the same git config keys.

[📷 05-rebase-only-what-changed.png]

## Conflicts, guided

- When a rebase stops, **the IDE's merge dialog opens on its own** with the conflicted files.
- The stack says **which layer and which commit** stopped (`refunds/refund-dialog, layer 4 of 5`, plus the commit subject) and how many files are still in conflict.
- When there are none left, the banner turns blue and the next step is **Continue**, one click.
- **Abort** asks first, because it also undoes the layers that were already rebased.
- The stack stays on screen during the rebase (with a detached HEAD, `gh stack view` alone doesn't know where it is).

[📷 06-conflicts-guided.png]

## Reviewing a layer

- **Show Layer Changes** opens the IDE diff with what a layer adds on top of the one below: the same changes its PR shows. If the layer below moved on, it compares from their common commit, so new stuff below doesn't show up as if the layer removed it.
- **Change Pull Request Base** puts a *Wrong base* PR back on top of the layer below (`gh pr edit <url> --base <branch>`).

[📷 07-review-a-layer-like-its-pr.png]

## Merging and closing

- **Merge Stack…** / **Merge Up to Here…** use GitHub's atomic stack merge (`gh stack merge`): the chosen PR and every one below it get merged together, **or none**.
- The dialog shows which layers go in, with their review and CI, and only the merge methods your repo allows (it remembers the last one). With a **merge queue**, they join the queue.
- It warns about what GitHub will check and `gh stack` doesn't look at: unapproved PRs, failing or running CI, conflicts. Drafts and layers without a PR can't be merged, and the dialog says why.
- A safety check I'm quite happy with: `gh stack merge 12` first tries `12` as a *stack* number. Upstack checks there's no stack with that number before running it, so you never merge the wrong thing.
- Afterwards, **Sync and Prune** (`gh stack sync --prune`) deletes the merged branches and rebases the rest.
- **Close Stack…**: unstack it, close its open PRs (with an optional comment) and delete its remote and local branches, each one a checkbox, in an order that never leaves a stack without branches. Local branches that differ from the remote are flagged and kept by default.

[📷 08-merge-or-close-the-stack.png]

## Every option says what it runs

- **Hover anything** (toolbar buttons, menu items, banner buttons, dialog options) and you see what it does **and the exact commands**, with your real branch and PR URL.
- A layer's right-click menu only has actions for that layer. Whole-stack actions live in the toolbar and in the right-click outside the rows, so you always know what you're touching.
- A **Log** tab keeps every command it ran and its output.

[📷 09-every-option-explains-itself.png]

## Setup

- **Check** (*Settings → Version Control → Upstack*) tells you which `gh` it uses and where, your account on each host, the `gh-stack` version, the repo's remotes and whether GitHub supports stacked PRs there. Whatever's missing has its fix next to it: **Install**, **Upgrade gh-stack**, **Log In…**.
- **Log In without a terminal**: it runs `gh auth login --web`, shows you the one-time code, copies it and opens GitHub. The token stays with `gh`; Upstack never sees it.
- If your `gh-stack` isn't the version the plugin was built against (v0.1.1), it tells you once. It doesn't block anything.

[📷 10-set-up-in-a-minute.png]

## When things go sideways

Stuff I hit using it on real repos, so it's handled:

- **Someone deleted a layer's branch** (or it went away with its PR): the layer is marked *Branch deleted* and a banner explains that `gh stack` can't rebase, sync or push without it. **Restore Branch** brings it back from the remote or from its last known commit; **Remove From Stack…** takes it out.
- **A whole stack whose branches are gone**: **Forget Stack** stops tracking it without touching anything else, or **Recreate on Another Base…** starts the same layers again.
- **Delete Layer…** for the top layer without a PR, and **Rename Branch…** for layers without a PR (here and on GitHub). Layers with a PR can't be renamed, because GitHub closes the PR of a renamed branch, and the menu tells you that.
- **A branch that's in two stacks** (a layer of one and the base of another): it says so and lists both.
- **Several remotes**: it asks once which one to use and remembers it per project.

## Also in the Pull Requests tool window

If you use IntelliJ's own *Pull Requests* tool window, its list and details get a **Stack** menu: ready / draft, labels, final-layer label and **Check Out Stack Locally** (`gh stack checkout <url>`).

## Details

- **IntelliJ IDEA 2026.2 or newer**, with the bundled Git plugin. The GitHub plugin is optional (only for the *Pull Requests* menu).
- Needs the [GitHub CLI](https://cli.github.com), signed in, and the `gh-stack` extension (`gh extension install github/gh-stack`). The plugin offers to install it if it's missing.
- Your repository needs GitHub's stacked pull requests. *Check* tells you if it doesn't have them.
- No internal IntelliJ APIs: the build fails if the Plugin Verifier finds any internal, deprecated or experimental API, so it shouldn't break on the next IDE update.
- Tested with ~200 unit and flow tests that run the real git against a fake `gh`.
- **Free.**

**Install:** *Settings → Plugins → Marketplace*, search for **Upstack**
**Source:** https://github.com/palmerovicdev/intellij-stacked-prs

It's a solo project, so any feedback helps a lot: what's confusing, what's missing, what annoys you, or what breaks, especially if your stacking workflow is different from mine. I'll be around in the comments. Thanks for reading all the way down here 🙏
