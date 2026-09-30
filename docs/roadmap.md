# Roadmap — próximas adiciones

Lo que se propone añadir a Stacklane después de la `0.1.0`, con su estado. No es un
compromiso: es la lista de la que se elige cada iteración, y el registro de lo que ya se
ofreció para no volver a ofrecerlo como si fuera nuevo.

Las propuestas se numeran con `P` y los cambios pequeños con `C`. **No se renumeran nunca.**

| Estado        | Qué significa                                                                 |
|---------------|-------------------------------------------------------------------------------|
| ✅ Hecha      | Publicada; se indica la versión                                               |
| 🟡 Propuesta  | Ofrecida y todavía sin decidir                                                |
| 👾 Para hacer | Para hacer, se van haciendo por orden en que aparezcan en el archivo          |
| ⏸️ No aceptada | Ofrecida y no elegida. No está descartada: puede volver, pero no como novedad |

### Versiones

Hasta la `1.0.0`, cada iteración elegida sube la versión media (`0.2.0`, `0.3.0`…) y una
corrección sin nada nuevo sube la última (`0.1.1`). La `1.0.0` queda para cuando P1 esté
hecha y el plugin salga al Marketplace (P40).

### Reglas que valen para todas

- **Nada de API interna.** `verifyPlugin` falla con cualquier uso interno, deprecado o
  experimental. Si una propuesta solo es posible así, se queda en investigación.
- **`gh` hace el trabajo.** Cada escritura es un comando que se podría escribir en la
  terminal, se ve en el diálogo y queda en el Log.
- **gh-stack v0.1.1 es la referencia.** Su JSON y sus códigos de salida se leyeron del
  código de esa versión; lo que dependa de otra versión se comprueba antes (P4).

---

## Hechas

| Qué | Versión |
|---|---|
| Ventana Stacks: pila como grafo, PR, draft/listo, labels, review, CI, HEAD y *needs rebase* | `0.1.0` |
| Nueva pila, añadir capa, publicar como drafts o listos, sync, rebase con conflictos | `0.1.0` |
| Listo/draft, labels y `stack-final` desde la pila y desde los menús de Pull Requests | `0.1.0` |
| Pestaña Log con cada comando y el comando en vivo en cada diálogo | `0.1.0` |
| Base libre al crear una pila: cualquier rama local o remota, también desde otra pila | `0.1.0` |
| Pilas locales sin ramas: se marcan, *Forget Stack* y *Recreate on Another Base…* | `0.2.0` |
| Pila nueva: limpia antes los nombres que retiene una pila sin ramas y recupera sus commits | `0.2.0` |
| Pila nueva: vista previa de la pila resultante y bases agrupadas en locales y remotas | `0.2.0` |
| Remotos con alias SSH (`git@github-personal:org/repo`) emparejados con sus PRs | `0.2.0` |
| Avisos y vista previa de comandos partidos en líneas al ancho del diálogo | `0.2.1` |

---

## Orden sugerido

1. **P1** — probar con PRs reales antes de construir encima.
2. **P3** — el fallo de las ramas que están en dos pilas.
3. **P15** — cerrar una pila entera, PRs y ramas, en el orden correcto: justo lo que, hecho
   a mano, dejó la pila huérfana que motivó la `0.2.0`.
4. **P44** — lo mismo para una capa suelta cuya rama desaparece dentro de una pila activa.
5. **P2** — red de seguridad para todo lo que venga.
6. **P8** — publicar decidiendo PR a PR, lo que `--open` no permite.
7. **P21 + P22** — base rota, tamaño y conflictos de cada capa; ya llega casi todo en la
   consulta que se hace hoy.
8. **P25** — ver el diff de una capa sola, que es el sentido de trabajar con pilas.
9. **P10 + P29** — moverse por la pila sin abrir la ventana.
10. **P9** — merge de la pila.

---

## Ronda del 2026-09-30 · Validar y endurecer

Lo que falta para fiarse de la `0.1.0` tal y como está.

### P1 · Probar con PRs reales, de principio a fin 🟡

Todo lo probado hasta ahora fue en un repositorio sin remoto: la ventana pinta la pila y
las acciones abren sus diálogos, pero `submit`, `sync`, `rebase` contra GitHub, los datos
de la consulta GraphQL y el menú *Stack* de Pull Requests no se han visto funcionar. El
menú depende de que la clave de la URL llegue en tiempo de ejecución (comprobado solo en
el código y con `DataKey.create`), y el refresco nativo depende de ejecutar
`Github.PullRequest.List.Reload` con `tryToExecute`, que tampoco se ha visto.

Primera entrega: un repositorio de prueba en GitHub y una lista de comprobación escrita
en `docs/` (crear pila, dos capas, publicar como drafts, listo por PR, labels,
`stack-final`, sync tras un merge, rebase con conflicto, menú en lista y en detalle,
refresco de la lista). El resultado de cada paso se copia al README. Lo que falle entra
como `C` o como `P`.

Primer contacto real (2026-09-30, repositorio `staffMobileApp`): la ventana, el Log y la
lista de pilas locales funcionaron; el checkout de una pila cuyo PR se había cerrado y
cuya rama se borró falló. De ahí salió la `0.2.0`.

### P2 · Tests de flujo con un `gh` falso 🟡

Hoy hay 21 tests de funciones puras (JSON, URLs, comandos y los planes de la `0.2.0`). `StackService` —estados,
reintento con `--remote`, avisos por código de salida, una escritura a la vez— no tiene
ninguno. `GhCli.locate()` ya da prioridad a la ruta de los ajustes, así que un script
que apunte los argumentos y conteste con salidas grabadas (códigos 2, 3, 6, 8, 9) sirve
de `gh` sin red.

Primera entrega: tests de `StackService` sobre ese script con el framework de pruebas de
la plataforma (`testFramework(TestFrameworkType.Platform)`; son unas librerías, no otro
IDE). Casos mínimos: los estados de la ventana, el reintento tras *multiple remotes*, el
aviso de conflicto con y sin `gh-stack-rebase-state`, y que una segunda escritura espere.

### P3 · Una rama que está en dos pilas 🟡

Desde que la base puede ser cualquier rama, una capa de la pila A puede ser la base de la
pila B. Estando en esa rama, `gh stack view --json` sale con código 6 (*belongs to
multiple stacks*) y `StackService.refreshNow` lo trata igual que el 2: la ventana dice
«no es parte de una pila», que es falso. Comprobado con gh-stack v0.1.1.

Primera entrega: separar el 6 del 2. Con el 6, decir «está en N pilas», listar esas pilas
(con el fichero local, ver P7) y abrir cada una haciendo checkout de su capa superior.

### P4 · Comprobar el entorno e iniciar sesión 🟡

Si falta `gh`, la extensión o la sesión, hoy se ve un error genérico: el de `gh auth` sale
tal cual en el aviso de *details unavailable*. La versión de gh-stack tampoco se mira, y
el plugin depende del JSON y los códigos de salida de la v0.1.1.

Primera entrega: en Settings, un botón *Check* y un resumen con la versión de `gh`,
`gh auth status` por host, `gh stack --version`, si el repositorio admite pilas (código 9)
y los remotos. Si la versión de gh-stack no es conocida, un aviso, no un bloqueo, y
*Upgrade* con `gh extension upgrade gh-stack`. Para iniciar sesión, *Log In* abre una
pestaña de la terminal del IDE con `gh auth login` (dependencia opcional del plugin
Terminal, como en Tasklane) y refresca al cerrarla.

### P5 · Refrescar menos y mejor 🟡

La ventana se refresca al mostrarse y con cada cambio real de git (la huella de ramas y
refs de `StackService.fingerprint`). Cada refresco son dos llamadas a GitHub: `gh stack
view --json` sincroniza los PRs por dentro y luego va la consulta GraphQL. Si se oculta y
se muestra la ventana varias veces seguidas, se repite todo.

Primera entrega: medir cuánto tarda un refresco con 3 y con 8 capas. Limitar el refresco
al mostrarse (una vez cada 30 s como mucho) y separar las dos lecturas: la pila local
siempre, GitHub solo si cambió un PR o ha pasado el intervalo. Es la base del sondeo de
P27.

### P6 · Pedir a gh-stack lo que falta 🟡

Varias cosas no se pueden hacer bien porque gh-stack no las expone en modo no
interactivo:

- el número de la pila en `view --json`, que `view --short` sí imprime;
- listar las pilas locales (hoy se lee `.git/gh-stack`, ver P7);
- `isDraft` en el JSON;
- reordenar, insertar, renombrar o plegar capas sin la interfaz de `gh stack modify`;
- olvidar una pila local cuyas ramas ya no existen: `unstack --local` solo actúa sobre la
  pila de la rama actual o sobre un número de pila de GitHub, y una pila de un solo PR no
  lo tiene. La `0.2.0` lo resuelve con cinco comandos (`StackPlans.forget`); un
  `gh stack unstack --local <rama>` los sustituiría.

Primera entrega: abrir issues (o PRs pequeños) en `github/gh-stack` con cada caso y el uso
concreto en un IDE. Se apunta aquí qué se aceptó para quitar los rodeos del plugin.

### P7 · El estado interno de gh-stack, solo como último recurso 🟡

`StackService.localStacks` lee `.git/gh-stack` para listar pilas cuando la rama actual no
está en ninguna y, desde la `0.2.0`, para saber cuáles se quedaron sin ramas y cuál era
el último commit de cada una (`head`), que es lo que permite recuperarlas. Es estado
interno de gh-stack: se lee solo el esquema 1, sin escribirlo nunca, pero puede cambiar en
cualquier versión.

Primera entrega: aislar esa lectura en una clase con un test por versión conocida de
gh-stack y un aviso en el Log si el esquema es otro. Se sustituye en cuanto P6 consiga un
`gh stack list --json` o P13 lo resuelva con la API.

---

## Ronda del 2026-09-30 · El flujo de la pila

### P8 · Publicar decidiendo PR a PR 🟡

`gh stack submit --auto` crea los PRs nuevos como draft con títulos generados, y `--open`
marca listos **todos** los PRs de la pila, también los drafts que ya existían. La
revisión inicial lo señaló: para dejar listo solo uno hay que publicar como draft y
marcarlo después. Es lo que hace el editor interactivo de gh-stack, que el plugin no
puede abrir.

Primera entrega: un diálogo *Publish…* con una fila por capa: título y cuerpo para las
nuevas (propuestos a partir de sus commits, editables) y un interruptor draft/listo para
todas. Se ejecuta `gh stack submit --auto` y, después, un `gh pr edit --title --body` por
PR nuevo y un `gh pr ready` o `--undo` por cada cambio. El Log enseña la secuencia entera.
Las dos acciones de hoy quedan como atajos.

### P9 · Merge de la pila 🟡

`gh stack merge <pr> --yes --squash|--merge|--rebase` fusiona de forma atómica hasta la
capa elegida y entra en la cola de merge si la base la usa. No está en el plugin.

Primera entrega: *Merge Up To This Layer…* en el menú de capa. El diálogo muestra qué capas
entran, su review y su CI, y el método (se recuerda el último). Al terminar ofrece
`gh stack sync --prune` para borrar las ramas fusionadas. Nunca sin confirmación.

### P10 · Navegar por la pila 🟡

gh-stack tiene `up`, `down`, `top`, `bottom` y `trunk`, que se saltan las capas
fusionadas. En el plugin solo hay doble clic sobre una capa.

Primera entrega: acciones *Stack Up / Down / Top / Bottom / Base* con el checkout del IDE
(el de `StackService.checkout`, con smart checkout) y la misma regla de saltar las
fusionadas. Sin atajo por defecto, asignables en Keymap, y visibles en *Find Action*, en
el widget de P29 y en el popup de ramas (P30).

### P11 · Rebase parcial y opciones 🟡

*Rebase Stack* ejecuta `gh stack rebase` entero, con fetch del trunk. Después de tocar una
capa intermedia lo normal es `--upstack` (de la actual a la cima). El aviso *needs
rebase* ofrece también el rebase completo.

Primera entrega: el botón con desplegable: completo, `--upstack`, `--downstack` y
`--no-trunk`. El aviso propone `--upstack` desde la primera capa que lo necesita. La
opción `--committer-date-is-author-date` pasa a los ajustes (P36).

### P12 · Conflictos guiados de principio a fin 🟡

Cuando `gh stack rebase` se para, hoy hay un aviso y una banda con *Resolve Conflicts*,
*Continue* y *Abort*. Hay que pulsar tres veces y no se sabe qué capa se está
rebasando.

Primera entrega: abrir el diálogo de conflictos solo al pararse. Cuando
`ChangeListManager` deje de ver conflictos, ofrecer *Continue* en un clic. La banda
indica qué capa se está rebasando si se puede saber sin leer estado interno de gh-stack
(con `REBASE_HEAD` y la rama de git). *Abort* sigue pidiendo confirmación.

### P13 · Traer una pila de GitHub 🟡

gh-stack usa la API REST `repos/{owner}/{repo}/stacks` (listar, crear, añadir, deshacer y
fusionar). Se comprobó que responde: en un repositorio sin pilas devuelve `[]`. Hoy solo
se puede traer una pila escribiendo su número o un PR.

Primera entrega: *Check Out a Stack…* con la lista de pilas del repositorio desde esa API
(número, PRs y autor) y checkout con `gh stack checkout <número>`. La API está en vista
previa: se lee de forma tolerante, como `StackJson`, y si falla se vuelve al diálogo de
texto.

### P14 · Enlazar PRs que ya existen 🟡

`gh stack link 41 42 43 [--base]` convierte en pila PRs abiertos sin tocar ramas locales,
y `gh stack link <pila> <pr>` añade PRs a una pila. Sirve a quien creó los PRs a mano o
con otra herramienta.

Primera entrega: un diálogo con los PRs abiertos (`gh pr list --json`), elegidos y
ordenados de abajo arriba, con la base opcional. También *Add to Stack…* desde el menú de
Pull Requests, con la misma clave de URL que ya se usa.

### P15 · Cerrar una pila entera: la pila, sus PRs y sus ramas 🟡

Lo que hay, leído del código de gh-stack v0.1.1 y de `gh`:

- `gh stack unstack` deshace la pila en GitHub y deja de seguirla en local; con `--local`,
  solo lo segundo. **No cierra PRs ni borra ramas.** GitHub deja apiladas las PRs en cola
  o con auto-merge.
- Un PR **no se puede borrar** en GitHub, solo cerrar: `gh pr close <url> --comment …
  --delete-branch`, que además borra la rama local y la remota. Se puede reabrir mientras
  la rama exista (o se restaure desde el PR).
- **El orden importa.** Si se borran las ramas antes de deshacer la pila, gh-stack la
  sigue registrando sin ramas: es exactamente lo que pasó con el PR #1020 y lo que la
  `0.2.0` tiene que limpiar después. Y cerrando de abajo arriba, las capas de encima se
  quedan con un PR cuya base desaparece.
- Las capas fusionadas se limpian con `gh stack sync --prune`.

Primera entrega: *Close Stack…* con casillas —deshacer la pila en GitHub, cerrar los PRs
abiertos (con comentario opcional), borrar ramas remotas, borrar ramas locales— y la lista
de cada PR y rama que se va a tocar. Se ejecuta en orden seguro: `unstack` primero, con
las ramas aún vivas, y después `gh pr close` de la cima hacia abajo. Confirmación
obligatoria y todo en el Log. Cerrar una sola capa de la cima entra aquí; una intermedia
necesita reestructurar la pila (P16).

### P16 · Reordenar, insertar, renombrar y plegar capas 🟡

Solo `gh stack modify` hace estas operaciones, y únicamente con su interfaz de terminal.
Renombrar la rama con git rompe el seguimiento de gh-stack, que guarda los nombres.

Primera entrega: *Restructure in Terminal…* abre `gh stack modify` en una pestaña de la
terminal del IDE (la dependencia de P4) y refresca al cerrarse. Hacerlo desde la propia
ventana espera a P6.

### P17 · Cambiar la base de una pila que ya existe 🟡

Desde la `0.1.0` la base se elige libremente al crear la pila, pero después no se puede
cambiar: gh-stack fija el trunk en `init` y no tiene comando para moverlo. Casos típicos:
la rama de release cambia, o la pila de un compañero en la que te apoyabas se fusiona.

Investigación primero: probar sobre una copia `unstack --local`, luego
`init --base <nueva> <capas…>` (adopta las ramas), `rebase` y `submit` (que actualiza las
bases de los PRs). Si es fiable, *Change Base…* muestra la secuencia completa y pide
confirmación. Si no lo es, pasa a P6 como petición.

### P18 · Llevar cambios a una capa inferior 🟡

Estando en la cima es fácil tocar algo que pertenece a la capa 1. Hoy hay que guardar los
cambios, cambiar de rama, commitear, rebasar hacia arriba y volver, todo a mano.

Investigación primero: *Commit to Layer…* en el diálogo de commit, que haga shelve,
checkout de la capa, unshelve, commit, `gh stack rebase --upstack` y vuelta, con un único
*Undo* si algo falla. Es la propuesta con más riesgo de esta ronda: primero se prueba
sobre copias, con conflictos incluidos.

### P19 · Commit y restack 🟡

Al commitear en una capa que no es la cima, las de arriba quedan desfasadas. La ventana
lo muestra (*needs rebase*), pero no lo ofrece en el momento.

Primera entrega: un `CheckinHandlerFactory` (API pública) que, tras commitear en una capa
intermedia, ofrezca *Rebase upstack now / Later / Always*. La opción *Always* queda en los
ajustes (P36).

### P20 · Nombres para las capas nuevas 🟡

*Add Layer* propone el prefijo de la cima (`feat/`), y `gh stack add -m` genera nombres
con la fecha si no se da ninguno.

Primera entrega: una plantilla en ajustes de proyecto con `{prefix}`, `{n}`, `{slug}` (del
mensaje de commit) y `{user}`. Ejemplo: `{user}/{prefix}{n}-{slug}`. El diálogo aplica la
plantilla y deja editar el resultado.

---

## Ronda del 2026-09-30 · El estado de los PRs

### P21 · Base del PR que no coincide con la capa de abajo 🟡

La consulta GraphQL ya trae `baseRefName` y no se usa. Si la base de un PR no es la capa
de debajo (`StackSnapshot.parentOf`), la pila en GitHub está rota: alguien cambió la base
a mano o un merge la movió. Caso real visto: una pila local con base `main` cuyo PR
apuntaba a `dev`.

Primera entrega: una pastilla *Base mismatch* en la capa, con el detalle en el tooltip, y
*Fix* que ejecuta `gh stack submit --auto` (actualiza las bases). Sale casi gratis.

### P22 · Tamaño y conflictos de cada capa 🟡

La gracia de apilar es que cada PR sea pequeño, pero la ventana no enseña tamaños.
GitHub da `additions`, `deletions`, `changedFiles`, `mergeable` y `mergeStateStatus` en la
misma consulta.

Primera entrega: `+120 −30 · 8 files` en la segunda línea, una pastilla *Conflicts* si
`mergeable` es `CONFLICTING` y *Behind* o *Blocked* según `mergeStateStatus`. Opcional:
un aviso de tamaño a partir de un umbral configurable.

### P23 · Revisores y conversaciones 🟡

Hoy solo se ve la decisión global de review. Faltan quién tiene pendiente revisar, quién
aprobó y cuántas conversaciones siguen abiertas.

Primera entrega: `reviewRequests`, `latestReviews` y el número de `reviewThreads` sin
resolver en la consulta. Iniciales de los revisores en la fila y el detalle en el tooltip.
*Request Review…* con `gh pr edit --add-reviewer`, para una capa o para toda la pila.

### P24 · CI con detalle y acciones 🟡

La pastilla de CI solo resume el último commit. Para ver qué falló hay que ir al navegador.

Primera entrega: al pulsar la pastilla, un popup con `gh pr checks <url> --json
name,state,link,workflow`, cada check con su enlace, y *Re-run Failed* con
`gh run rerun <id> --failed`.

### P25 · El diff de una capa contra su base 🟡

Revisar una capa sola —solo lo que añade sobre la de abajo— es el sentido de apilar. Git4Idea
tiene `GitBrancher.compareAny(rama, otra, repos)` y `showDiff(rama, otra, repos)`
(comprobado por reflexión en la 2026.2.2), que abren la comparación nativa del IDE.

Primera entrega: *Show Layer Changes* en el menú de capa (y Ctrl+doble clic), que compara
la capa con `parentOf(capa)`. Verificar con `verifyPlugin` que esos métodos no llevan
anotaciones internas antes de prometerlo.

### P26 · Abrir el PR en la ventana Pull Requests del IDE 🟡

*Open Pull Request* lleva al navegador. Abrirlo en la ventana Pull Requests del IDE
necesita su identificador y su controlador, y los dos son API interna del plugin GitHub.

Investigación: buscar una vía pública (una URL que el plugin GitHub sepa abrir, una
acción que acepte la URL). Si no la hay, queda descartada por la regla de no usar API
interna, y se apunta aquí para no volver a proponerla.

### P27 · Avisos cuando algo cambia 🟡

Con el sondeo de P5, comparar el estado anterior con el nuevo y avisar cuando una capa se
aprueba, recibe cambios pedidos, falla su CI o se fusiona.

Primera entrega: notificaciones agrupadas por pila, con *Open* y *Show in Stacks*, y un
interruptor por tipo de evento en los ajustes (P38). Sin sondeo si la ventana lleva
tiempo oculta, salvo que el usuario lo pida.

---

## Ronda del 2026-09-30 · Integración con el IDE

### P28 · Varias pilas a la vista 🟡

La ventana enseña la pila de la rama actual y, solo fuera de una pila, la lista de pilas
locales. Quien lleva dos o tres pilas a la vez no las ve juntas.

Primera entrega: un selector de pila en la cabecera (como el de repositorio) con todas
las pilas locales. Elegir una no hace checkout: solo cambia lo que se ve, con sus
acciones activas donde tenga sentido. Depende de P7 y, con P13, suma las remotas.

### P29 · Widget en la barra de estado 🟡

`payment-api · 2/3 · Draft · ✓` para la rama actual. Un clic abre Stacks y el menú trae
la navegación de P10. `StatusBarWidgetFactory` es API pública y Tasklane ya lo hace (su
P4).

### P30 · La pila en el popup de ramas y en el menú Git 🟡

El descriptor de Git4Idea instalado define `Git.Branch` (acciones de una rama en el popup),
`Git.MainMenu` y `Git.Ongoing.Rebase.Actions`. Se pueden añadir acciones por id, igual que
el menú de Pull Requests.

Primera entrega: *New Stack Based on This Branch* y *Add Layer* en el popup de ramas;
*Continue* y *Abort gh stack rebase* junto a las acciones de rebase en curso. Antes hay
que comprobar si el popup de ramas expone la rama elegida con una clave pública. Si no,
se añaden solo las acciones que no la necesitan.

### P31 · Las capas en el log de Git 🟡

Desde el log, *New Stack From Here* en `Git.Log.ContextMenu`, y *Show in Log* en el menú
de capa para saltar a su commit. Validar que saltar a un commit del log es API pública;
si no lo es, solo la primera parte.

### P32 · Capas en Search Everywhere 🟡

Un contribuidor que lista las capas de las pilas locales con el título de su PR. Intro
hace checkout y Shift+Intro abre el PR. Útil con muchas pilas (P28).

### P33 · Teclado y lector de pantalla 🟡

La lista se usa con flechas e Intro, pero *Refresh* no tiene atajo y las filas no tienen
nombre accesible: el renderizador devuelve paneles que un lector de pantalla no lee.

Primera entrega: `use-shortcut-of="Refresh"`, comprobar que el menú de capa se abre con
Shift+F10, y `accessibleName` por fila («feat/payment-api, PR 13,
draft, capa 2 de 3»). Tasklane hizo lo mismo en su P34.

---

## Ronda del 2026-09-30 · Configuración

Hoy los ajustes son dos: la ruta de `gh` y el nombre de la label final, más el remoto
elegido por proyecto, que no se puede cambiar (ver P37).

### P34 · Base por defecto de una pila nueva 🟡

Desde la `0.1.0` se propone la rama actual; si se adopta una rama suelta, la rama por
defecto del repositorio. Hay equipos que siempre apilan sobre `develop` o sobre la
release en curso.

Primera entrega: un ajuste por proyecto: *Current branch* (hoy), *Repository default*,
*Always:* `<rama>`, o *Last used*. El diálogo lo usa como valor inicial y sigue dejando
elegir cualquier otra.

### P35 · Ajustes de publicación 🟡

Primera entrega, con ámbito de proyecto:

- modo por defecto: drafts o listos, y si se confirma antes de marcar listos;
- labels que se añaden a todo PR nuevo de una pila (por ejemplo `stacked`);
- revisores y asignados por defecto (`@me` incluido);
- la label final: única por pila (hoy es fija), moverla a la capa nueva al añadir una, o
  ponerla sola en la cima al publicar.

### P36 · Ajustes de sync y rebase 🟡

`--prune` al sincronizar, `--committer-date-is-author-date` al rebasar, el alcance por
defecto del rebase (P11) y *Always* para el restack tras commit (P19).

### P37 · El remoto, visible y editable 🟡

Cuando gh-stack no sabe a qué remoto publicar, se pregunta y se guarda en el workspace
(`StacklaneProjectSettings.remote`), pero no hay dónde verlo ni cambiarlo: si se elige
mal, la única salida es borrar `.idea/workspace.xml`.

Primera entrega: una página de proyecto en Settings con el remoto (o *Ask*) y el
repositorio que se muestra cuando hay varios. Puede salir como corrección (`0.1.x`).

### P38 · Ajustes de la vista 🟡

Qué pastillas se ven (labels, review, CI, tamaño), filas compactas, capas fusionadas
plegadas en una fila «N merged», intervalo de sondeo (P5), avisos por evento (P27) y un
modo que también escriba en el Log los comandos de lectura, para diagnosticar.

### P39 · Convenciones de equipo en el repositorio 🟡

Los ajustes de hoy son del IDE, y los de P34–P36 serían del workspace. Un equipo quiere
compartir la plantilla de ramas (P20), las labels de publicación y el nombre de la label
final.

Primera entrega: `.idea/stacklane.xml` versionable con esas convenciones. Tiene
prioridad sobre los ajustes globales, y la página de Settings indica de dónde sale cada
valor.

---

## Ronda del 2026-09-30 · Publicación

### P40 · CI, firma y Marketplace 🟡

Tasklane tiene `build.yml`, `nightly.yml` y `release.yml`. Stacklane no tiene ninguno.

Primera entrega: `build.yml` con tests y `verifyPlugin` estricto contra la 2026.2 y
`recommended()` (en CI sí se descargan IDEs), y `release.yml` con firma y publicación como
en Tasklane. Ficha del Marketplace con capturas en `docs/screenshots/`, revisión del
nombre (disponibilidad de «Stacklane» y que no use marcas de GitHub) y del icono. Con P1
hecha, esto es la `1.0.0`.

### P41 · Traducción al español 🟡

Todos los textos pasan por `StacklaneBundle`, así que basta con
`StacklaneBundle_es.properties`. Hay que revisar los que llevan `{0,choice,…}`.

### P42 · Windows y Linux 🟡

`GhCommands.display` cita al estilo POSIX (`'…'`), que no sirve para pegar en PowerShell;
`GhCli.locate` ya busca `gh.exe`, pero no se ha probado. Los tests unitarios pueden correr
en Linux en CI; en Windows hay que probar a mano la ruta de `gh`, los finales de línea y
el Log.

---

## Ronda del 2026-09-30 (segunda) · Lo que enseñó la `0.2.0`

### P43 · Pilas y worktrees de git 🟡

Al probar la `0.2.0` se vio que gh-stack guarda su estado en el directorio git **del
worktree** (`.git/worktrees/<nombre>/`), no en el del repositorio: desde un worktree no se
ven las pilas del checkout principal, ni al revés. El plugin lee `.git/gh-stack` del
checkout donde está abierto el proyecto, así que en un worktree la lista de pilas locales
sale vacía o distinta.

Primera entrega: comprobarlo con los comandos de escritura (init, add, submit) desde un
worktree, documentar el comportamiento en el README y hacer que el plugin lea el fichero
del mismo directorio git que usa gh-stack (`git rev-parse --git-dir`). Si hace falta
compartir pilas entre worktrees, pasa a P6.

### P44 · Una capa cuya rama desaparece dentro de una pila activa 🟡

La `0.2.0` resuelve la pila a la que no le queda ninguna rama. Queda el caso a medias:
se cierra el PR de una capa y se borra su rama, pero las demás siguen. No se ha visto
todavía qué devuelve `gh stack view --json` con una capa sin rama, ni si `sync` o
`rebase` la saltan.

Primera entrega: probarlo sobre una copia y pintar esa capa como *Branch deleted*, con
*Remove From Stack* (lo que permita gh-stack, o `modify` en la terminal, P16) y
*Restore Branch*, que la recrea en su último commit conocido, igual que la `0.2.0`.

---

## Cambios pequeños a lo que ya existe (`0.1.x`)

Encontrados al probar y al releer el código. Cada uno cabe en una corrección.

| | Qué | Dónde |
|---|---|---|
| 🟡 C1 | Las capas sin PR dicen lo mismo dos veces: pastilla *No PR* y *Not published yet*. Dejar una sola | `StackRowRenderer.layerRow` |
| 🟡 C2 | La línea del grafo casi no se ve en tema oscuro (`RAIL` `0x3D444D`) | `StackColors` |
| 🟡 C3 | El progreso de *Check Out Stack* enseña la URL entera del PR; mejor `#13` | `StackFlows.checkoutFromPullRequest` |
| 🟡 C4 | `StackRow.Layer.position` no se usa: enseñar `2/3` en el tooltip o en la fila | `StackRows` |
| 🟡 C5 | `repo!!` en el aviso de *needs rebase*; no puede fallar hoy, pero sobra | `StackPanel.renderBanners` |
| 🟡 C6 | La página de ajustes está en *Tools*; encaja mejor en *Version Control* | `plugin.xml` |
| ✅ C7 | La caché de build de Gradle devolvía clases de test compiladas contra firmas viejas (fallos falsos, incluso tras `clean`): desactivada `0.2.0` | `gradle.properties` |
