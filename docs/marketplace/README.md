# Marketplace y Reddit

Lo necesario para presentar Upstack: diez diapositivas de 2400×1520 (el doble del mínimo de
1200×760 del Marketplace, todas con la misma proporción) y el post de Reddit.

| Diapositiva | Qué enseña |
|---|---|
| `01-stacked-prs-in-your-ide.png` | El IDE entero con la ventana Stacks: la pila con PRs, review, CI y labels |
| `02-every-layer-at-a-glance.png` | La ventana, con *Wrong base* y *Behind*, y el tooltip de una capa |
| `03-start-a-stack.png` | *New Stack* (con el grafo) y *Add Layer* |
| `04-publish-your-way.png` | *Publish Stack*: listo o draft por capa, título y descripción de los nuevos |
| `05-rebase-only-what-changed.png` | La banda *needs rebase* y el desplegable de rebase |
| `06-conflicts-guided.png` | La banda de un rebase parado y el diálogo de conflictos del IDE |
| `07-review-a-layer-like-its-pr.png` | *Show Layer Changes*: el diff de una capa |
| `08-merge-or-close-the-stack.png` | *Merge Stack* y *Close Stack* |
| `09-every-option-explains-itself.png` | El menú de una capa con la ayuda y los comandos de una opción |
| `10-set-up-in-a-minute.png` | *Check* del entorno y *Log In* |

`reddit-post.md` es el post (pensado para r/Jetbrains), con un `[📷 …]` donde va cada imagen.

## Publicar

- **Marketplace → Media**: subir las diez en orden; la primera es la que sale en los
  resultados de búsqueda. Van aparte de la `<description>` de `plugin.xml`, que no las enlaza.
- **README**: las diez ilustran la portada del proyecto ([README](../../README.md)), que las
  enlaza por su nombre. Si se renombra o se añade una, hay que cambiarla también allí.
- **Reddit**: un post de imágenes en el orden del fichero, o un post de texto con las imágenes
  insertadas donde marca cada `[📷 …]`. El enlace de instalación solo funciona cuando el plugin
  ya está aprobado en el Marketplace.

## Rehacer las capturas

Todo sale de un repositorio de demo, sin red y sin tocar GitHub:

- `tools/demo-repo.sh` crea `build/marketplace/storefront`: un proyecto TypeScript con una pila
  de cinco capas hecha con el gh-stack real, y un `origin` que parece
  `github.com/acme/storefront` pero es un repositorio desnudo al lado (`core.sshCommand`).
- `tools/gh` es el `gh` que usa el IDE de prueba: los comandos locales de `gh stack` van al
  real, y lo que necesitaría GitHub (PRs, review, CI, labels, cuenta, código de login) sale de
  `tools/demo-github.json`. Cada escena elige sus PRs en `build/marketplace/state/scenario`.
- `tools/capture.groovy` es un guion fijo que el IDE de prueba ejecuta al abrir el proyecto
  (`IdeStartupScripts`): coloca cada escena, pinta las ventanas a PNG y escribe `DONE`.
- `tools/compose.py` monta cada diapositiva en HTML y la renderiza con Chrome headless.

```bash
docs/marketplace/tools/capture.sh            # repo de demo nuevo + guion en el sandbox
./gradlew runIde "-PlocalIdePath=$HOME/Applications/IntelliJ IDEA.app" \
  "-Porg.gradle.java.installations.paths=$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "-PrunIdeProject=$PWD/build/marketplace/storefront"
# cuando build/marketplace/shots/capture.log dice DONE:
python3 docs/marketplace/tools/compose.py    # -> docs/marketplace/*.png
docs/marketplace/tools/capture.sh --clean    # quita el guion y devuelve los ajustes del sandbox
```

La captura cambia ajustes del IDE de prueba (la ruta de `gh`, Code Vision, la barra de
navegación, el diff unificado); `--clean` los devuelve como estaban. Las capturas en bruto
quedan en `build/marketplace/shots/raw`.
