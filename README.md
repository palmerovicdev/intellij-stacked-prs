# Upstack

Plugin de IntelliJ IDEA para trabajar con **stacked pull requests de GitHub** desde el IDE.
Es una interfaz visual sobre `gh stack` (extensión [github/gh-stack](https://github.com/github/gh-stack))
y `gh pr`. Cada botón ejecuta el mismo comando que escribirías en la terminal, así que se
puede alternar entre IDE y terminal en cualquier momento.

## Qué hace

**Ventana Stacks** (a la derecha):

- La pila como un grafo, desde la capa superior hasta el trunk. Por capa: rama, número de
  PR, estado (draft, listo, fusionado, en cola, cerrado o sin PR), título, tamaño
  (`+120 −30`), labels, decisión de review y CI. También indica la rama actual (HEAD), las
  capas que necesitan rebase, los PRs cuya base en GitHub no es la capa de debajo
  (*Wrong base*) y los que GitHub no puede fusionar sin conflictos (*Conflicts*) o sin
  ponerse al día con su base (*Behind*).
- Barra: refrescar, **nueva pila**, **añadir capa encima**, **publicar como drafts**,
  **publicar listos para revisión**, **sync**, **rebase** (desplegable: upstack desde la
  capa actual, pila entera, downstack y capas entre sí), **fusionar**, **cerrar** y ajustes.
- Menú de cada capa (clic derecho sobre ella): solo lo de esa capa. Checkout, **ver los
  cambios de la capa** (el diff del IDE contra la de debajo, lo mismo que enseña su PR),
  abrir o copiar el PR, **rebase upstack desde aquí**, **fusionar hasta aquí**, **cambiar la base del PR** a la capa
  de debajo cuando no coincide, **marcar listo / volver a draft**,
  **labels…** y **marcar como capa final** (`stack-final`). Clic derecho fuera de las filas
  o sobre la base: lo de toda la pila (añadir capa, publicar, fusionar y cerrar).
- **Cada opción explica lo que hace y el comando que ejecuta** al pasar el ratón: barra,
  menús, botones de las bandas y opciones de los diálogos. Los comandos llevan los datos
  reales (rama, URL del PR), cada uno en su bloque con fondo, y las URLs en azul; los
  enlaces de la ventana vacía lo enseñan debajo, en gris.
- **Rebase upstack**: tras cambiar una capa intermedia, lleva sus commits a las de encima
  sin fetch y sin tocar el trunk, así que solo salen los conflictos de ese cambio. Se ofrece
  al commitear desde el IDE en una capa que no es la cima (o se hace siempre, según los
  ajustes) y en la banda *needs rebase*, que distingue una capa atrasada (upstack) de la
  capa de abajo atrás del trunk (rebase de toda la pila).
- Al terminar un rebase, una banda ofrece **Push Stack** hasta que las ramas se suben (desde
  el plugin o desde la terminal). El primer rebase de cada repositorio pregunta si
  activar **git rerere**, como gh-stack en la terminal, y guarda la respuesta en las mismas
  claves.
- **Conflictos guiados**: cuando un rebase se para, el diálogo de conflictos del IDE se abre
  solo. La banda dice en qué capa y en qué commit se paró y cuántos ficheros siguen en
  conflicto; cuando el IDE ya no ve ninguno, se vuelve azul con **Continuar**. *Abortar*
  pide confirmación: deshace también las capas ya rebasadas.
- **Pilas locales sin ramas** (se cerró el PR y se borró la rama a mano): se marcan
  *Branches deleted* y se pueden **olvidar** o **recrear sobre otra base**, recuperando el
  último commit de cada rama. *New Stack* también limpia antes los nombres que retienen.
- **Una capa sin rama dentro de una pila activa** (se borró a mano o al cerrar su PR):
  se marca *Branch deleted* (o *Only on origin*) y una banda avisa de que gh-stack no puede
  rebasar, sincronizar ni subir la pila sin ella. **Restore Branch** (o doble clic) la
  recrea desde el remoto o en su último commit conocido; **Remove From Stack…** la saca: la
  de arriba sin PR directamente, y cualquier otra recuperando la rama y con
  `gh stack modify` en la terminal, que una banda guía hasta que la capa sale.
- **Borrar la capa de arriba sin PR** (*Delete Layer…*): sale de la pila sin
  `gh stack modify`, con su rama local y, si se marca, la remota.
- **Renombrar la rama de una capa sin PR** (*Rename Branch…*), aquí y en GitHub si se subió,
  y **cambiar el título de un PR** (*Edit Title…*), por separado.
- **Publicar una capa desde su menú** (*Publish as Draft…* / *Publish Ready for Review…*),
  con el nombre de la rama como título propuesto y la descripción de sus commits, si no hay
  capas sin PR encima.
- **Una rama en varias pilas** (capa de una y base de otra, o base de varias): se dice en
  cuántas está y se listan primero, marcadas *HEAD*. Cada una se abre desde su capa más alta
  que no sea base de otra pila, que es desde donde gh-stack la enseña.
- **Fusionar la pila** (*Merge Stack…* o, desde una capa, *Merge Up to Here…*): GitHub
  fusiona ese PR y todos los de debajo de una vez, o ninguno. El diálogo enseña qué capas
  entran, su review y su CI, y el método (solo los que admite el repositorio; se recuerda el
  último). Con cola de merge, entran en la cola. Después, **Sync and Prune** borra las ramas
  fusionadas, desde el aviso o desde la banda que se queda hasta hacerlo.
- **Cerrar una pila entera** (*Close Stack…*): deshacerla en GitHub, cerrar sus PRs
  abiertos con un comentario opcional y borrar sus ramas remotas y locales. El diálogo lista
  cada PR y rama que toca y los comandos en el orden en que se ejecutan.
- **Comprobar el entorno** (*Settings | Version Control | Upstack*, botón *Check*): qué
  `gh` hay y dónde, con qué cuenta en cada host, qué versión de gh-stack, los remotos del
  repositorio y si GitHub admite pilas en él. Lo que falta trae su arreglo al lado:
  *Install*, *Upgrade gh-stack* o *Log In…*.
- **Iniciar sesión sin terminal** (*Log In…*): `gh auth login --web` pide un código de un
  solo uso, y un diálogo lo enseña, lo copia y abre la página de GitHub donde se escribe.
  Se ofrece también en la ventana, en una banda, cuando los datos de los PRs fallan por
  falta de sesión, y en el aviso de una escritura que falla por lo mismo.
- **Aviso si gh-stack no es la v0.1.1**, una vez por versión: el plugin lee su JSON y sus
  códigos de salida de esa versión. No bloquea nada.
- Pestaña **Log** con cada comando ejecutado y su salida.

**Menú “Stack” en la ventana Pull Requests** de IntelliJ (lista y detalle): listo / draft,
labels, `stack-final` y *Check Out Stack Locally* (`gh stack checkout <url>`).

| Operación | Comando |
|---|---|
| Nueva pila | `gh stack init [--base rama] capas…`: la base puede ser **cualquier rama**, local o solo remota, también una capa de otra pila |
| Añadir capa | `gh stack add rama` (con `gh stack top` antes si no estás arriba; commit opcional `-A`/`-u` `-m`) |
| Publicar decidiendo PR a PR | `gh stack submit --auto`, y después `gh pr edit <rama> --title … --body …` por cada PR nuevo y `gh pr ready` (o `--undo`) por cada cambio |
| Publicar como drafts | `gh stack submit --auto` |
| Publicar listos | `gh stack submit --auto --open` (antes muestra qué PRs cambian: `--open` también marca listos los drafts existentes) |
| Listo / draft | `gh pr ready <url>` / `gh pr ready <url> --undo` |
| Cambiar la base del PR | `gh pr edit <url> --base <capa de debajo>` |
| Ver los cambios de una capa | el diff del IDE, equivalente a `git diff <capa de debajo>...<capa>` (con `git merge-base` antes si la de debajo avanzó) |
| Labels | `gh pr edit <url> --add-label … --remove-label …` |
| Capa final | igual, con la label configurada (por defecto `stack-final`) |
| Sync / rebase | `gh stack sync` / `gh stack rebase` (`--continue`, `--abort`) |
| Rebase upstack | `gh stack rebase --upstack --no-trunk`, con checkout del IDE antes si no estás en esa capa y vuelta al terminar |
| Otros rebase | `gh stack rebase --downstack` y `gh stack rebase --no-trunk` |
| Push tras el rebase | `gh stack push` |
| Fusionar | `gh stack merge <pr> --yes --squash` (o `--merge`, `--rebase`; sin método con cola de merge), tras comprobar con `gh api repos/OWNER/REPO/stacks/<pr>` que no hay una pila con ese número |
| Tras fusionar | `gh stack sync --prune` |
| Recuperar la rama de una capa | `git branch <rama> <último commit>` o `git branch --track <rama> origin/<rama>` |
| Borrar la capa de arriba sin PR | `git switch <capa de debajo>` si estás en ella, `gh stack unstack --local`, `gh stack init --base <trunk> <demás capas>`, `git branch -D <rama>` (y `git push origin --delete <rama>`) |
| Renombrar la rama de una capa sin PR | `gh stack unstack --local`, `gh api --method POST repos/OWNER/REPO/branches/<rama>/rename -f new_name=<nueva>` si está en GitHub, `git branch -m`, `gh stack init` con el nombre nuevo |
| Título de un PR | `gh pr edit <url> --title …` |
| Publicar una capa | `gh stack submit --auto`, `gh pr edit <rama> --title … --body …` y, si es lista, `gh pr ready <rama>` |
| Pregunta de rerere | `git config rerere.enabled true` y `git config rerere.autoupdate true`, o `git config gh-stack.rerere-declined true` |
| Comprobar el entorno | `gh --version`, `gh auth status --json hosts`, `gh stack --version` y `gh api repos/OWNER/REPO/stacks?per_page=1` (solo leen) |
| Iniciar sesión | `gh auth login --web --hostname <host>` |
| Actualizar gh-stack | `gh extension upgrade gh-stack` |

Cada diálogo muestra en vivo la línea `$ gh …` que va a ejecutar.

## Sin API interna

El `build.gradle.kts` configura el Plugin Verifier para que **falle** si aparece cualquier
uso de API interna, deprecada, experimental, *override-only* o *non-extendable*. Se ejecuta
contra la IDEA instalada, sin descargar nada:

```text
Plugin com.upstack:0.10.0 against IU-262.10315.125: Compatible
```

La integración con la ventana Pull Requests no compila contra el plugin GitHub: ninguna de
sus clases entra en el classpath. Se apoya en tres contratos de nombre, sin referencias a
clases:

1. Los ids de los grupos de menú (`Github.PullRequest.ToolWindow.List.Popup` y
   `Github.PullRequest.Details.Popup`), en `upstack-github.xml`.
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

- [GitHub CLI](https://cli.github.com) con sesión iniciada (`gh auth login`, o *Log In…*
  desde el plugin).
- La extensión `gh-stack` (`gh extension install github/gh-stack`), en su v0.1.1. Si falta,
  el plugin ofrece instalarla; si es otra versión, avisa.

*Settings | Version Control | Upstack | Check* dice cuál de las tres cosas falta.
- IntelliJ IDEA 2026.2 o posterior, con el plugin Git activo.

## Desarrollo

Todo corre contra la IDEA instalada en `/Applications/IntelliJ IDEA.app` (2026.2.2, build
262.10315.125) y su JBR 25. No se descarga ningún IDE ni JDK; Gradle, IPGP 2.18.1 y Kotlin
2.4.20 son las mismas versiones que ya usa Tasklane.

```bash
./gradlew test                  # tests unitarios y de flujo (con un gh falso, sin red)
./gradlew verifyPlugin          # Plugin Verifier estricto contra la IDEA local
./gradlew runIde -PrunIdeProject=/ruta/a/un/repo   # IDE de prueba con ese proyecto
./gradlew buildPlugin           # build/distributions/upstack-<versión>.zip
```

Para probarlo en el IDE de trabajo: *Settings → Plugins → ⚙ → Install Plugin from Disk…* y
elegir el zip.

## Código

```text
gh/        GhCli (ejecuta gh sin TTY y lo cancela con la corrutina), GhCommands (cada comando), Environment (qué gh, sesión y gh-stack hay)
stack/     modelo, parser JSON, StackService (estado + operaciones), log, rerere, handler de commit
ui/        ventana, lista con el grafo, diálogos
actions/   acciones (barra, menú de capa, menú de Pull Requests), PrTarget, StackFlows
settings/  ruta de gh, nombre de la label final, qué hacer tras un commit y la comprobación del entorno; remoto y último método de merge por proyecto
```

Los tests de `src/test/.../flow` corren dentro de un IDE headless con el framework de pruebas de
la plataforma: un repositorio git real en un directorio temporal y `gh` sustituido por
`FakeGh`, un script que apunta los argumentos de cada llamada y contesta con salidas grabadas
(se le pasa al plugin como ruta de `gh` en los ajustes). Comprueban de la acción al comando:
qué se ejecuta, en qué orden y qué aviso sale. git es el real.

`StackService` lee con `gh stack view --json` más una consulta GraphQL para draft, labels,
review, CI, base, tamaño y estado de merge de todos los PRs a la vez. Refresca cuando git cambia de verdad: rama, commits o
refs. Las escrituras van de una en una, con progreso cancelable, registro en Log y refresco
del VFS y de Git4Idea al terminar.

## Estado

**Verificado**

- Compila, 196 tests en verde y Plugin Verifier estricto limpio contra IU-262.10315.125.
- `StackService` y los flujos que escriben en GitHub, con un `gh` falso: los estados de la
  ventana, el reintento con `--remote` tras *multiple remotes*, los avisos por código de
  salida (3 con y sin rebase parado, 8, 9, 10), el corte de una secuencia y su limpieza, una
  escritura a la vez, la pregunta de rerere, publicar, ready/draft, cambiar la base y la label
  de capa final.
- Un rebase parado de verdad (git real, gh falso): la capa, su posición y el commit en
  conflicto salen de `rebase-merge/head-name` y `REBASE_HEAD`; la pila sigue en pantalla
  aunque `view` falle con HEAD suelto; los conflictos se cuentan hasta resolverlos; el
  diálogo de conflictos se abre solo, y *Abortar* no hace nada si se cancela.
- El rebase upstack, con gh-stack v0.1.1 real sobre un repositorio de prueba: tras avanzar
  `main` y commitear en la capa del medio, la de encima recibe el commit y ninguna capa se
  rebasa sobre `main`. La de abajo sigue marcada, porque eso solo lo arregla el rebase
  completo.
- En el IDE de prueba con un repositorio de demo: la ventana pinta la pila y las acciones
  se abren. Salieron dos fallos, ya corregidos: una llamada de Git4Idea prohibida en el EDT
  y los radio buttons del diálogo de nueva capa.
- Una capa sin rama (P44), con gh-stack v0.1.1 real: qué devuelven `view`, `rebase`, `sync`,
  `push` y `modify`, y que *drop* en `modify` la saca una vez recuperada y rebasada. Borrar la
  capa de arriba con `unstack --local` + `init`, también con gh-stack real. En el IDE, solo con
  tests de flujo: la pastilla, las bandas y el diálogo aún no se han visto pintados.
- La salida JSON y los códigos de salida corresponden al código de gh-stack v0.1.1.
- El entorno (P4), con gh 2.102.0 real: lo que imprimen `gh --version`, `gh stack --version`
  y `gh auth status --json hosts` con y sin sesión, el 404 de la API de pilas y la salida 4
  de `gh api` sin sesión. `gh auth login --web` sin terminal imprime el código y espera (se
  probó con una configuración de gh aparte y se cortó antes de autorizar). En el IDE, con
  tests de flujo: el resumen de los ajustes y el diálogo de *Log In* (el código, el cierre
  al terminar gh, el error y la cancelación).
- Pilas con base `develop` y con base en una capa de otra pila, creadas con gh-stack real.
- Una rama en dos pilas, con gh-stack real: `view --json` sale con 6 en la capa que es base
  de otra y en un `main` con dos pilas encima, y con 0 en las demás capas de cada una.
  `init` desde esa capa sale con 5.

**Pendiente de probar**

- La `0.12.0` con un `gh` de verdad: un inicio de sesión completo, autorizando en el
  navegador, y *Log In* en GitHub Enterprise. El resumen de *Check* y el diálogo de *Log In*
  ya se vieron pintados en el IDE de prueba, con el `gh` falso de las capturas del
  Marketplace.

- Con PRs reales: los menús de Pull Requests (necesitan una cuenta GitHub en el IDE),
  submit, sync y rebase contra GitHub, y el refresco nativo tras escribir.
- Varios repositorios en un mismo proyecto. Los remotos múltiples tienen tests de flujo, pero
  no se han visto contra GitHub.
- La lista de una rama en varias pilas (`0.3.0`) en el IDE de prueba: los datos se
  comprobaron con gh-stack real, el pintado todavía no.
- *Publish Stack…* (`0.4.0`) contra GitHub: los comandos tienen tests, el diálogo y la
  secuencia real todavía no.
- La `0.7.0` en el IDE de prueba: las pastillas nuevas de cada fila, *Show Layer Changes* y
  *Change Pull Request Base*. El tooltip nuevo se comprobó pintando su HTML con el kit HTML
  del IDE, no dentro del IDE. *Change Pull Request Base* sobre un PR de una pila registrada
  en GitHub: gh-stack no toca esas bases (la API de pilas las gestiona) y no se sabe si
  GitHub acepta el cambio.

## Decisiones

- **`stack-final` es única por pila.** Al marcar una capa como final desde Stacks se quita
  de las otras capas que la tuvieran. Si la label no existe en el repositorio, se ofrece
  crearla.
- **Publicar listos pide confirmación** y enseña qué PRs cambian. La opción *Publish as
  Drafts* queda en el mismo diálogo. Para elegir PR a PR está *Publish Stack…*.
- **El menú de una capa solo tiene acciones de esa capa.** Las de toda la pila dicen
  *Stack* y están en la barra y en el clic derecho fuera de las filas. gh-stack no publica
  una capa suelta (`gh stack submit` sube todas): el *Publish* de una capa solo se activa si
  no hay capas sin PR encima, y avisa de las de debajo que salen con ella.
- **Renombrar solo ramas sin PR.** GitHub cierra el PR cuya rama se renombra, y un PR no
  puede cambiar de rama. Con PR, la acción se ve desactivada y lo explica. El título es otra
  acción, *Edit Title…*.
- **Menús de lista, no `JPopupMenu`**, para que cada opción enseñe su tooltip. El menú
  *Stack* de Pull Requests es del plugin GitHub: ahí la explicación y el comando solo salen
  en la barra de estado.
- **Iniciar sesión sin terminal.** La API de pestañas de la terminal del IDE es experimental
  en la 2026.2 y la anterior está deprecada: ninguna pasa el Plugin Verifier. No hace falta:
  sin terminal, `gh auth login --web` no pregunta nada, imprime el código de un solo uso y
  espera. Va sin `--git-protocol`, para no cambiar el protocolo de git del host. El token lo
  guarda gh; el plugin solo ve el código, que también queda en el Log.
- **La versión de gh-stack se avisa, no se exige.** Una vez por sesión se mira
  `gh stack --version`; si no es la v0.1.1, sale un aviso (uno por versión) con *Upgrade* si
  es anterior. Con una posterior todo sigue igual: el JSON se lee de forma tolerante.
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
- **Cerrar una pila va en un orden fijo**: `gh stack unstack` primero, con las ramas aún
  vivas (si no, gh-stack se queda con una pila sin ramas); después `gh pr close` de la cima
  hacia abajo, `git push --delete` y `git branch -D`. Si GitHub no deshace la pila (PRs en
  cola o con auto-merge), gh-stack sale bien pero la sigue registrando: entonces no se
  cierra ni se borra nada. Las ramas remotas solo se borran si se cierran antes los PRs
  abiertos, y nunca una rama que es la base de otra pila. Antes del diálogo se pregunta al
  remoto qué ramas tiene (`git ls-remote`), porque lo que git sabe en local puede estar
  atrasado.
- **Borrar la capa de arriba sin `modify`.** gh-stack 0.1.1 solo saca capas con
  `gh stack modify`, que es interactivo. Para la de arriba sin PR, el plugin deja de seguir la
  pila en local y la vuelve a crear con las demás capas, que gh-stack adopta tal cual. Sus PRs
  se encuentran al leer la pila, y el siguiente `submit` o `sync` adopta la pila de GitHub
  porque tiene los mismos PRs. No se ofrece con capas fusionadas o en cola: la pila de GitHub
  las guarda y la nueva no las tendría. Si `init` falla, se vuelve a crear la pila como estaba.
- **Una capa sin rama no se puede sacar con `modify`**: gh-stack se niega a abrir la pila
  (*failed to check linearity*). Por eso *Remove From Stack…* recupera antes la rama.
- **Checkout** con el de Git4Idea (smart checkout y diálogo de cambios locales), no con
  `gh stack checkout`, salvo al traer una pila que solo existe en GitHub.

## Versiones

Versión actual: **0.12.2**. Qué trae cada una: [CHANGELOG.md](CHANGELOG.md).

Las capturas del Marketplace, el post de Reddit y cómo rehacerlos están en
[docs/marketplace](docs/marketplace/README.md).

## Roadmap

Lo que se puede añadir, con su estado y el orden sugerido, está en
[docs/roadmap.md](docs/roadmap.md), con el mismo formato que el de Tasklane.
