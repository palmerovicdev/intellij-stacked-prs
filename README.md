# Stacklane

Plugin de IntelliJ IDEA para trabajar con **stacked pull requests de GitHub** desde el IDE.
Es una interfaz visual sobre `gh stack` (extensión [github/gh-stack](https://github.com/github/gh-stack))
y `gh pr`. Cada botón ejecuta el mismo comando que escribirías en la terminal, así que se
puede alternar entre IDE y terminal en cualquier momento.

## Qué hace

**Ventana Stacks** (a la derecha):

- La pila como un grafo, desde la capa superior hasta el trunk. Por capa: rama, número de
  PR, estado (draft, listo, fusionado, en cola, cerrado o sin PR), título, labels, decisión
  de review y CI. También indica la rama actual (HEAD) y las capas que necesitan rebase.
- Barra: refrescar, **nueva pila**, **añadir capa encima**, **publicar como drafts**,
  **publicar listos para revisión**, **sync**, **rebase** y ajustes.
- Menú de cada capa: checkout, abrir o copiar el PR, **marcar listo / volver a draft**,
  **labels…** y **marcar como capa final** (`stack-final`).
- Aviso de rebase parado con *Resolver conflictos*, *Continuar* y *Abortar*.
- **Pilas locales sin ramas** (se cerró el PR y se borró la rama a mano): se marcan
  *Branches deleted* y se pueden **olvidar** o **recrear sobre otra base**, recuperando el
  último commit de cada rama. *New Stack* también limpia antes los nombres que retienen.
- Pestaña **Log** con cada comando ejecutado y su salida.

**Menú “Stack” en la ventana Pull Requests** de IntelliJ (lista y detalle): listo / draft,
labels, `stack-final` y *Check Out Stack Locally* (`gh stack checkout <url>`).

| Operación | Comando |
|---|---|
| Nueva pila | `gh stack init [--base rama] capas…`: la base puede ser **cualquier rama**, local o solo remota, también una capa de otra pila |
| Añadir capa | `gh stack add rama` (con `gh stack top` antes si no estás arriba; commit opcional `-A`/`-u` `-m`) |
| Publicar como drafts | `gh stack submit --auto` |
| Publicar listos | `gh stack submit --auto --open` (antes muestra qué PRs cambian: `--open` también marca listos los drafts existentes) |
| Listo / draft | `gh pr ready <url>` / `gh pr ready <url> --undo` |
| Labels | `gh pr edit <url> --add-label … --remove-label …` |
| Capa final | igual, con la label configurada (por defecto `stack-final`) |
| Sync / rebase | `gh stack sync` / `gh stack rebase` (`--continue`, `--abort`) |

Cada diálogo muestra en vivo la línea `$ gh …` que va a ejecutar.

## Sin API interna

El `build.gradle.kts` configura el Plugin Verifier para que **falle** si aparece cualquier
uso de API interna, deprecada, experimental, *override-only* o *non-extendable*. Se ejecuta
contra la IDEA instalada, sin descargar nada:

```text
Plugin com.stacklane:0.1.0 against IU-262.10315.125: Compatible
```

La integración con la ventana Pull Requests no compila contra el plugin GitHub: ninguna de
sus clases entra en el classpath. Se apoya en tres contratos de nombre, sin referencias a
clases:

1. Los ids de los grupos de menú (`Github.PullRequest.ToolWindow.List.Popup` y
   `Github.PullRequest.Details.Popup`), en `stacklane-github.xml`.
2. El nombre de la `DataKey` de la URL del PR (`org.jetbrains.plugins.github.pullrequest.url`).
   Se crea una clave propia con ese nombre en vez de importar `GHPRActionKeys`, que es
   `@ApiStatus.Internal`. Comprobado en la 2026.2.2: `DataKey.create` con ese nombre
   devuelve la misma instancia que usa el plugin GitHub.
3. Los ids de sus acciones de recarga, para refrescar la lista y el detalle después de
   escribir.

JetBrains no garantiza esos nombres. Si cambian, esas acciones desaparecen de los menús de
Pull Requests (o no refrescan), sin errores; la ventana Stacks no depende de ellos.

Lo que **no** se hace, porque la plataforma no lo permite sin API interna: insertar una
pestaña propia dentro de la ventana Pull Requests (su gestor elimina los contenidos ajenos)
ni añadir botones a su cabecera. Por eso la vista principal es una ventana propia.

## Requisitos para usarlo

- [GitHub CLI](https://cli.github.com) con sesión iniciada (`gh auth login`).
- La extensión `gh-stack` (`gh extension install github/gh-stack`). Si falta, el plugin
  ofrece instalarla.
- IntelliJ IDEA 2026.2 o posterior, con el plugin Git activo.

## Desarrollo

Todo corre contra la IDEA instalada en `/Applications/IntelliJ IDEA.app` (2026.2.2, build
262.10315.125) y su JBR 25. No se descarga ningún IDE ni JDK; Gradle, IPGP 2.18.1 y Kotlin
2.4.20 son las mismas versiones que ya usa Tasklane.

```bash
./gradlew test                  # tests unitarios
./gradlew verifyPlugin          # Plugin Verifier estricto contra la IDEA local
./gradlew runIde -PrunIdeProject=/ruta/a/un/repo   # IDE de prueba con ese proyecto
./gradlew buildPlugin           # build/distributions/stacklane-<versión>.zip
```

Para probarlo en el IDE de trabajo: *Settings → Plugins → ⚙ → Install Plugin from Disk…* y
elegir el zip.

## Código

```text
gh/        GhCli (ejecuta gh sin TTY y lo cancela con la corrutina), GhCommands (cada comando)
stack/     modelo, parser JSON, StackService (estado + operaciones), log
ui/        ventana, lista con el grafo, diálogos
actions/   acciones (barra, menú de capa, menú de Pull Requests), PrTarget, StackFlows
settings/  ruta de gh, nombre de la label final, remoto preferido por proyecto
```

`StackService` lee con `gh stack view --json` más una consulta GraphQL para draft, labels,
review y CI de todos los PRs a la vez. Refresca cuando git cambia de verdad: rama, commits o
refs. Las escrituras van de una en una, con progreso cancelable, registro en Log y refresco
del VFS y de Git4Idea al terminar.

## Estado

**Verificado**

- Compila, 21 tests en verde y Plugin Verifier estricto limpio contra IU-262.10315.125.
- En el IDE de prueba con un repositorio de demo: la ventana pinta la pila y las acciones
  se abren. Salieron dos fallos, ya corregidos: una llamada de Git4Idea prohibida en el EDT
  y los radio buttons del diálogo de nueva capa.
- La salida JSON y los códigos de salida corresponden al código de gh-stack v0.1.1.
- Pilas con base `develop` y con base en una capa de otra pila, creadas con gh-stack real.

**Pendiente de probar**

- Con PRs reales: los menús de Pull Requests (necesitan una cuenta GitHub en el IDE),
  submit, sync y rebase contra GitHub, y el refresco nativo tras escribir.
- Varios repositorios en un mismo proyecto y remotos múltiples.
- Una rama que está en dos pilas (capa de una y base de otra): la ventana dice que no
  está en ninguna. Es la P3 del roadmap.

## Decisiones

- **`stack-final` es única por pila.** Al marcar una capa como final desde Stacks se quita
  de las otras capas que la tuvieran. Si la label no existe en el repositorio, se ofrece
  crearla.
- **Publicar listos pide confirmación** y enseña qué PRs cambian. La opción *Publish as
  Drafts* queda en el mismo diálogo.
- **Varios remotos**: si gh-stack no sabe cuál usar, se pregunta una vez y se recuerda por
  proyecto.
- **La base de una pila nueva es libre.** Se propone la rama actual (como
  `git checkout -b`), salvo cuando se adopta una rama suelta como primera capa. Si estás
  en una capa de otra pila, primero se hace checkout de su base, porque gh-stack no deja
  empezar una pila desde una capa.
- **Olvidar una pila sin ramas** sin tocar nada más. gh-stack 0.1.1 solo deja de seguir
  una pila desde una de sus ramas o por su número en GitHub, que una pila de un solo PR no
  tiene. El plugin recrea una de sus ramas **en el commit actual**, cambia a ella (mismo
  commit: no cambia ningún fichero ni se pierden cambios locales), ejecuta
  `gh stack unstack --local`, vuelve y la borra. Si algo falla a medias, deshace solo lo que
  hizo. Todo aparece en el Log y en la vista previa del diálogo.
- **Checkout** con el de Git4Idea (smart checkout y diálogo de cambios locales), no con
  `gh stack checkout`, salvo al traer una pila que solo existe en GitHub.

## Versiones

Versión actual: **0.2.0**. Qué trae cada una: [CHANGELOG.md](CHANGELOG.md).

## Roadmap

Lo que se puede añadir, con su estado y el orden sugerido, está en
[docs/roadmap.md](docs/roadmap.md), con el mismo formato que el de Tasklane.
