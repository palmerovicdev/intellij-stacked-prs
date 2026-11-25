# Manual de Upstack

El manual completo, en español: qué hace cada opción, qué comando ejecuta y por qué funciona
así. La portada del proyecto, con las capturas, es el [README](../README.md); compilar,
probar, versionar y publicar están en [`development.md`](development.md).

**Índice:** [Qué hace](#qué-hace) · [Requisitos para usarlo](#requisitos-para-usarlo) ·
[Decisiones](#decisiones)

Upstack es un plugin de IntelliJ IDEA para trabajar con **stacked pull requests de GitHub**
desde el IDE. Es una interfaz visual sobre `gh stack` (extensión
[github/gh-stack](https://github.com/github/gh-stack)) y `gh pr`. Cada botón ejecuta el mismo
comando que escribirías en la terminal, así que se puede alternar entre IDE y terminal en
cualquier momento.

## Qué hace

**Ventana Stacks** (a la derecha):

- La pila como un grafo, desde la capa superior hasta el trunk. Por capa: rama, número de
  PR, estado (draft, listo, fusionado, en cola, cerrado o sin PR), título, tamaño
  (`+120 −30`), labels, decisión de review y CI. También indica la rama actual (HEAD), las
  capas que necesitan rebase, los PRs cuya base en GitHub no es la capa de debajo
  (*Wrong base*) y los que GitHub no puede fusionar sin conflictos (*Conflicts*) o sin
  ponerse al día con su base (*Behind*).
- Barra: refrescar, **fusionar**, **cerrar**, **nueva pila**, **añadir capa encima**,
  **publicar como drafts**, **publicar listos para revisión**, **sync**, **rebase**
  (desplegable: upstack desde la capa actual, pila entera, downstack y capas entre sí) y
  ajustes. Fusionar y cerrar van justo tras refrescar para que no se pierdan en el desborde
  (›) cuando la ventana es estrecha.
- **Refresco**: la pila se relee cuando git cambia (rama, commits o refs), al volver a la
  ventana, al terminar cada comando y con *Refresh*. Además, mientras la pestaña *Stack* está
  en pantalla, se relee sola cada 60 segundos (`gh stack view --json` y el estado de los PRs
  con `gh api graphql`), para que lleguen las revisiones, los checks que terminan y lo hecho
  fuera del IDE. Nunca mientras corre un comando, y cuenta desde la última lectura. El
  intervalo se cambia en *Settings | Version Control | Upstack*, *Refresh the stack every*: 0
  lo apaga; si no, como mínimo 10 segundos.
- Menú de cada capa (clic derecho sobre ella): solo lo de esa capa. En la lista de pilas,
  el menú de cada pila: cerrarla (*Close Stack…*) o, si ya no tiene ramas, olvidarla o
  recrearla. Checkout, **ver los
  cambios de la capa** (el diff del IDE contra la de debajo, lo mismo que enseña su PR),
  abrir o copiar el PR, **ver sus checks** (*Checks…*), **rebase upstack desde aquí**, **fusionar hasta aquí**, **cambiar la base del PR** a la capa
  de debajo cuando no coincide, **marcar listo / volver a draft**,
  **labels…** y **marcar como capa final** (`stack-final`). Clic derecho fuera de las filas
  o sobre la base: lo de toda la pila (añadir capa, publicar, fusionar y cerrar).
- **Checks de un PR**: pulsar la pastilla *Checks* de una capa (o *Checks…* en su menú) abre
  una ventana con todos los checks de su PR: su estado (✗ falló, ● en marcha, ✓ pasó), quién
  los lanza (GitHub Actions u otra app) y un resumen arriba. Se leen al abrirla
  (`gh pr checks`), no en cada refresco; *Refresh* los vuelve a leer. En cada uno que falla:
  - *Why it failed*: las últimas líneas del log de los pasos que fallaron, dentro de la
    ventana, sin horas ni colores y solo las del job de ese check
    (`gh run view <run> --log-failed`). En un check de otra app, lo que esa app publica; el
    detalle completo está en su página.
  - *Re-run*: vuelve a lanzar los jobs que fallaron (y los cancelados) de esa ejecución, no
    los que pasaron (`gh run rerun <run> --failed`), y relee la ventana y la pila. **Solo
    los checks de GitHub Actions se pueden relanzar desde aquí**: los de otras apps (Vercel,
    CircleCI…) solo los relanza la app que los creó, y la ventana lo dice en vez del botón
    («Only Vercel can re-run it»). Necesita permiso de escritura en el repositorio; si GitHub
    lo rechaza, la ventana enseña el error de gh.
  - *Open on GitHub*: la página del check en el navegador.

  Es una ventana por PR y no bloquea el IDE; la línea de abajo enseña el último comando.
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
  el plugin o desde la terminal). Llega con la lectura de la pila rebasada, la misma que quita
  la banda *needs rebase*: nunca salen las dos a la vez. El primer rebase de cada repositorio pregunta si
  activar **git rerere**, como gh-stack en la terminal, y guarda la respuesta en las mismas
  claves.
- **Conflictos guiados**: cuando un rebase se para, el diálogo de conflictos del IDE se abre
  solo. La banda dice en qué capa y en qué commit se paró y cuántos ficheros siguen en
  conflicto; cuando el IDE ya no ve ninguno, se vuelve azul con **Continuar**. *Abortar*
  pide confirmación: deshace también las capas ya rebasadas. La banda se va en cuanto
  *Continuar* o *Abortar* terminan, sin esperar a que se vuelva a leer la pila.
- **Pilas locales sin ramas** (se cerró el PR y se borró la rama a mano): se marcan
  *Branches deleted* y se pueden **olvidar** o **recrear sobre otra base**, recuperando el
  último commit de cada rama. Se pueden elegir varias en la lista (Mayúsculas o Cmd/Ctrl +
  clic) y olvidarlas todas a la vez (*Forget N Stacks*, una sola confirmación).
  *New Stack* también limpia antes los nombres que retienen.
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
  con el nombre de la rama como título propuesto y la descripción de sus commits, en
  cualquier capa sin PR: las de encima se quedan sin el.
- **Una rama en varias pilas** (capa de una y base de otra, o base de varias): se dice en
  cuántas está y se listan primero, marcadas *HEAD*. Cada una se abre desde su capa más alta
  que no sea base de otra pila, que es desde donde gh-stack la enseña.
- **Cerrar una pila desde la lista** (*Close Stack…* en el menú de su fila): gh-stack solo
  sabe qué pila cerrar desde una de sus ramas, y en la base de varias (dos pilas sobre
  `main`) no hay ninguna. Cambia antes a una capa que solo tiene esa pila (`git checkout`),
  espera a que se cargue y abre el diálogo de siempre de *Close Stack…*. Si se cancela, HEAD
  se queda en esa capa. Sin una capa local solo suya (todas son base de otra pila, o solo
  están en el remoto) sale desactivada y dice por qué. No se ofrece en las capas, ni con
  varias pilas elegidas, ni en las pilas sin ramas (esas se olvidan).
- **Fusionar la pila** (*Merge Stack…* o, desde una capa, *Merge Up to Here…*): GitHub
  fusiona ese PR y todos los de debajo de una vez, o ninguno. El diálogo enseña qué capas
  entran, su review y su CI, y el método (solo los que admite el repositorio; se recuerda el
  último). Con cola de merge, entran en la cola. Después, **Sync and Prune** borra las ramas
  fusionadas, desde el aviso o desde la banda que se queda hasta hacerlo.
- **La label de capa final** (*Settings | Version Control | Upstack*, grupo *Final-layer
  label*): nombre, color (selector o código hex) y descripción, por defecto `stack-final`,
  `8250DF` y "Top layer of a stack of pull requests". Cuando Upstack crea la label (*Create It
  and Retry*, o antes de fusionar) usa ese color y esa descripción, con `--force`: si ya existe,
  la actualiza.
- **Poner la label antes de fusionar** (opcional, **apagado por defecto**): con la casilla
  *Before Merge Stack, add the label to the last pull request being merged if it does not have
  it*, si el PR de arriba de lo que se fusiona no tiene la label, *Merge Stack…* y *Merge Up to
  Here…* primero la crean o actualizan, se la ponen a ese PR y se la quitan a las otras capas que
  la tengan (como *Mark as Final Layer*), y después fusionan. El diálogo enseña todos los
  comandos; si uno falla, no se fusiona nada.
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
- **Aviso si gh-stack no es la v0.2.1 ni la v0.1.1**, una vez por versión: el plugin lee su JSON y sus
  códigos de salida de esa versión. No bloquea nada.
- Pestaña **Log** con cada comando ejecutado y su salida.

**Menú “Stack” en la ventana Pull Requests** de IntelliJ (lista y detalle): listo / draft,
labels, `stack-final` y *Check Out Stack Locally* (`gh stack checkout <url>`).

| Operación | Comando |
|---|---|
| Nueva pila | `gh stack init [--base rama] capas…`: la base puede ser **cualquier rama**, local o solo remota, también una capa de otra pila. Las capas se autocompletan con las ramas del proyecto, como en *Añadir capa*; una que solo está en el remoto, antes `git branch --track rama origin/rama` |
| Añadir capa | `gh stack add rama` (con `gh stack top` antes si no estás arriba; commit opcional `-A`/`-u` `-m`; una rama que solo está en el remoto, antes `git branch --track rama origin/rama`) |
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
| Label antes de fusionar (si está activado y el PR de arriba no la tiene) | `gh label create <label> --repo OWNER/REPO --color <hex> --description <texto> --force`, `gh pr edit <url> --add-label <label>` y `gh pr edit <url> --remove-label <label>` en las otras capas que la tengan, antes de `gh stack merge` |
| Tras fusionar | `gh stack sync --prune` |
| Cerrar una pila desde la lista | `git checkout <capa solo de esa pila>` y después lo de *Close Stack…*: `gh stack unstack`, `gh pr close <url>`, `git push origin --delete …`, `git switch <trunk>` y `git branch -D …` |
| Recuperar la rama de una capa | `git branch <rama> <último commit>` o `git branch --track <rama> origin/<rama>` |
| Borrar la capa de arriba sin PR | `git switch <capa de debajo>` si estás en ella, `gh stack unstack --local`, `gh stack init --base <trunk> <demás capas>`, `git branch -D <rama>` (y `git push origin --delete <rama>`) |
| Renombrar la rama de una capa sin PR | `gh stack unstack --local`, `gh api --method POST repos/OWNER/REPO/branches/<rama>/rename -f new_name=<nueva>` si está en GitHub, `git branch -m`, `gh stack init` con el nombre nuevo |
| Título de un PR | `gh pr edit <url> --title …` |
| Checks de un PR | `gh pr checks <pr> --repo OWNER/REPO --json name,state,bucket,link,workflow,description` (solo lee) |
| Por qué falló un check | `gh run view <run> --repo OWNER/REPO --log-failed` (solo lee; GitHub Actions) |
| Relanzar un check | `gh run rerun <run> --failed --repo OWNER/REPO` (solo GitHub Actions) |
| Publicar una capa | `gh stack submit --auto`, `gh pr edit <rama> --title … --body …` y, si es lista, `gh pr ready <rama>`. Con capas sin PR encima, antes `gh stack unstack --local` y `gh stack init --base <trunk> <capas hasta ella>`, y después otra vez con todas |
| Pregunta de rerere | `git config rerere.enabled true` y `git config rerere.autoupdate true`, o `git config gh-stack.rerere-declined true` |
| Comprobar el entorno | `gh --version`, `gh auth status --json hosts`, `gh stack --version` y `gh api repos/OWNER/REPO/stacks?per_page=1` (solo leen) |
| Iniciar sesión | `gh auth login --web --hostname <host>` |
| Actualizar gh-stack | `gh extension upgrade gh-stack` |

Cada diálogo muestra en vivo la línea `$ gh …` que va a ejecutar.

## Requisitos para usarlo

- [GitHub CLI](https://cli.github.com) con sesión iniciada (`gh auth login`, o *Log In…*
  desde el plugin).
- La extensión `gh-stack` (`gh extension install github/gh-stack`), en su v0.2.1 o v0.1.1. Si falta,
  el plugin ofrece instalarla; si es otra versión, avisa.
- IntelliJ IDEA 2026.2 o posterior, con el plugin Git activo.

*Settings | Version Control | Upstack | Check* dice cuál de las tres primeras cosas falta.

## Decisiones

- **`stack-final` es única por pila.** Al marcar una capa como final desde Stacks se quita
  de las otras capas que la tuvieran. Si la label no existe en el repositorio, se ofrece
  crearla, con el color y la descripción de *Settings*.
- **Publicar listos pide confirmación** y enseña qué PRs cambian. La opción *Publish as
  Drafts* queda en el mismo diálogo. Para elegir PR a PR está *Publish Stack…*.
- **El menú de una capa solo tiene acciones de esa capa.** Las de toda la pila dicen
  *Stack* y están en la barra y en el clic derecho fuera de las filas. gh-stack no publica
  una capa suelta (`gh stack submit` sube todas las que no tienen PR): el *Publish* de una
  capa deja de seguir la pila en local, la vuelve a crear solo hasta ella para el `submit` y
  después entera, así que las de encima se quedan sin PR. Con capas fusionadas o en cola no
  se puede, y avisa de las de debajo que salen con ella.
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
  `gh stack --version`; si no es la v0.2.1 ni la v0.1.1, sale un aviso (uno por versión) con *Upgrade* si
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
