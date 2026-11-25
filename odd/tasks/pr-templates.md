# PR templates and per-layer Markdown files (0.15.12)

Objective: PR title/body templates, a Markdown file per layer as the source of a PR's title and body, and the same choice in the publish dialogs. Branch: feat/pr-templates (from feat/toolbar-and-dialog-fixes). Delivery: ask-on-risk, no PR yet. Test runner: `./gradlew test --tests <class>` against the installed IDEA (never verifyPlugin, never download an IDE). Engram mirror: pending (engram tools not available in this session).

## Specs

- **S1 — Templates.** User: "que nos permita configurar plantillas de titulos, y body para prs ... las plantillas se pueden agregar y tener varias". Acceptance: Settings | Version Control | Upstack has a list of templates (name, title, body): add, edit, remove, several. Decision (L2): stored in IDE settings (global), not in the repo.
- **S2 — A Markdown file as the PR's text.** User: "nos permita tener un archivo .md e la raiz del proyecto que sea el md que se coge para conformar el pr, con titulo y body". Decision (L2): "Un md por capa" (one file per layer). Assumptions (not asked): the file is `<repo root>/.upstack/<branch>.md`; the first `# ` heading is the title, everything after it is the body.
- **S3 — Apply a template to the file.** User: "al cual se le puede aplicar una plantilla ... cuando se da en apply template te permite cambiar el md para que tome la plantilla". Acceptance: an *Apply Template* action picks one of the templates and rewrites the layer's md with it.
- **S4 — Publishing takes the text from the md.** User: "al subir el pr a draft o ready se ponga el titulo y body como dice el md". Acceptance: when a layer is in file mode, Publish as Draft / Ready for Review sets the PR title and body from the md as it is at that moment.
- **S5 — The dialogs offer both ways.** User: "fijate que debe existir la posibilidad de hacer lo mismo en el editor de pr, o sea puedo seleccionar si quiero editraar en un archivo md o en el mismo editor que se muestra cuando vas a crear el pr" (the publish dialog with Title and Description). Acceptance: both publish dialogs (single layer and Publish Stack, per new layer) let you choose to edit in the dialog or in the md.
- **S6 — Template or empty in the dialog editor.** User: "y en este editor entonces te debe dejar elegir una plantilla o empezar vacio". Acceptance: in the dialog's fields you can pick a template or start empty.
- **S7 — Version.** User: "una version menor menor mas, o sea 0.15.+" → 0.15.12 (0.15.11 is the last; Marketplace has 0.14.0). Bump `pluginVersion`, CHANGELOG (Spanish), `changeNotes` (English), manual/roadmap where they describe publishing.
- **S8 — Edit title AND body of an existing PR.** User (screenshot of the layer menu on a Ready PR #1347, with *Edit Title…* in it): "aqui debe dejar editar el titulo y body". Acceptance: that menu entry edits the title and the description (body) of the PR, with the same editor as the publish dialogs (S5, S6: dialog or md, template or empty).
- **A1 (assumption)** Templates may use `{branch}` and `{commits}` (the commit list the dialog already proposes as body); without placeholders a title template would be a fixed string.

## Tasks

| ID | Specs | Plan | Route | Status | Evidence |
|----|-------|------|-------|--------|----------|
| T1 | S1, S2, A1 | `PrTemplate` + `PrTemplates` (render placeholders) in settings; `PrFile` (path, parse, render) | inline | pending | |
| T2 | S1 | Settings UI: template list editor | inline | pending | |
| T3 | S2-S6 | Shared `PrTextEditor` (mode, template combo, Apply, file link) used by PublishLayerDialog and PublishDialog rows | inline | pending | |
| T3b | S8 | *Edit Title…* → edit title and body (`gh pr edit --title --body`), body read with `gh pr view --json body`, same `PrTextEditor` | inline | pending | |
| T4 | S7 | Version 0.15.12, CHANGELOG, changeNotes, manual; buildPlugin | inline | pending | |

Route declaration: all inline (one understood feature, no parallel independent units, no writer trigger).

## Log

- L1 (2026-11-25): "mira vamos a hacer una version menor menor mas, o sea 0.15.+ que nos permita configurar plantillas de titulos, y body para prs, nos permita tener un archivo .md e la raiz del proyecto que sea el md que se coge para conformar el pr, con titulo y body, y al cual se le puede aplicar una plantilla, las plantillas se pueden agregar y tener varias, y cuando se da en apply template te permite cambiar el md para que tome la plantilla y al subir el pr a draft o ready se ponga el titulo y body como dice el md, fijate que debe existir la posibilidad de hacer lo mismo en el editor de pr, o sea puedo seleccionar si quiero editraar en un archivo md o en el mismo editor que se muestra cuando vas a crear el pr o sea este [screenshot: Publish fix/paged-seaches-with-more-than-50 as Draft: Pull request, Title, Description, command preview]"
- L2 (2026-11-25): "y en este editor entonces te debe dejar elegir una plantilla o empezar vacio". Answers to my questions: md → "Un md por capa"; templates → "Ajustes del IDE".
- L3 (2026-11-25): [screenshot of the layer context menu of fix/leaked-secrets #1347 Ready: Check Out Branch, Show Layer Changes, Open Pull Request in Browser, Checks…, Copy Pull Request Link, Rebase Upstack from Here, Rename Branch…, Merge Up to Here…, Convert to Draft, Edit Title…, Labels…, Mark as Final Layer] "aqui debe dejar editar el titulo y body"
