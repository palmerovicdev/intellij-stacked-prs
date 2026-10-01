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

| Qué                                                                                                                                                    | Versión                 |
|--------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------|
| Ventana Stacks: pila como grafo, PR, draft/listo, labels, review, CI, HEAD y *needs rebase*                                                            | `0.1.0`                 |
| Nueva pila, añadir capa, publicar como drafts o listos, sync, rebase con conflictos                                                                    | `0.1.0`                 |
| Listo/draft, labels y `stack-final` desde la pila y desde los menús de Pull Requests                                                                   | `0.1.0`                 |
| Pestaña Log con cada comando y el comando en vivo en cada diálogo                                                                                      | `0.1.0`                 |
| Base libre al crear una pila: cualquier rama local o remota, también desde otra pila                                                                   | `0.1.0`                 |
| Pilas locales sin ramas: se marcan, *Forget Stack* y *Recreate on Another Base…*                                                                       | `0.2.0`                 |
| Pila nueva: limpia antes los nombres que retiene una pila sin ramas y recupera sus commits                                                             | `0.2.0`                 |
| Pila nueva: vista previa de la pila resultante y bases agrupadas en locales y remotas                                                                  | `0.2.0`                 |
| Remotos con alias SSH (`git@github-personal:org/repo`) emparejados con sus PRs                                                                         | `0.2.0`                 |
| Avisos y vista previa de comandos partidos en líneas al ancho del diálogo                                                                              | `0.2.1`                 |
| Una rama en varias pilas: en cuántas está, cuáles, y cada una se abre desde una rama que no sea base de otra (P3)                                      | `0.3.0`                 |
| *Publish Stack…*: draft o listo PR a PR, y título y descripción de los PRs nuevos (P8)                                                                 | `0.4.0`                 |
| Menú de la pila: acciones de toda la pila rotuladas *Stack*, iconos en todo y clic derecho sobre la fila bajo el ratón (C8–C10)                        | `0.4.0`                 |
| Rebase upstack sin trunk: desplegable de rebase, *Rebase Upstack from Here* y banda *needs rebase* que distingue capa atrasada de trunk atrasado (P11) | `0.5.0`                 |
| Tras un commit del IDE en una capa intermedia, ofrecer el rebase upstack, o hacerlo siempre (P19)                                                      | `0.5.0`                 |
| *Push Stack* al terminar un rebase y la pregunta de git rerere que gh-stack solo hace en la terminal (P45, P46)                                        | `0.5.0`                 |
| *Got it* que explica el rebase upstack la primera vez (P47)                                                                                            | `0.5.0`                 |
| Cada opción explica qué hace y el comando que ejecuta, con sus datos reales (P48)                                                                      | `0.6.0`                 |
| Banda *Push Stack* tras un rebase, hasta que las ramas se suben (P49)                                                                                  | `0.6.0`                 |
| Menú de una capa solo con lo de esa capa; lo de la pila, fuera de las filas (C11)                                                                      | `0.6.0`                 |
| *Wrong base* y *Change Pull Request Base* cuando el PR no apunta a la capa de debajo (P21)                                                             | `0.7.0`                 |
| Tamaño, *Conflicts* y *Behind* de cada capa (P22)                                                                                                      | `0.7.0`                 |
| *Show Layer Changes*: el diff de una capa contra la de debajo (P25)                                                                                    | `0.7.0`                 |
| Tooltips más estrechos, comandos en bloque con fondo y URLs en azul (C12); C1–C4 y C6                                                                  | `0.7.0`                 |
| Tests de flujo de `StackService` y de las escrituras en GitHub con un `gh` falso (P2)                                                                  | sin versión: solo tests |
| *Close Stack…*: deshacer la pila, cerrar sus PRs y borrar sus ramas, en orden seguro (P15)                                                             | `0.8.0`                 |

---

## Orden sugerido

Revisado el 2026-09-30, tras la `0.6.0`. Criterio: lo que ya dolió usando el plugin, lo que
cuesta poco y lo que hace falta antes de escribir en GitHub cosas que no se deshacen.

1. ~~**P21 + P22 + P25**, con **C1–C4** y **C6**~~ — hechas en la `0.7.0`.
2. ~~**P2**, acotada a los flujos que escriben en GitHub~~ — hecha (2026-09-30), sin versión:
   solo tests.
3. ~~**P15** — cerrar una pila entera, PRs y ramas, en el orden correcto~~ — hecha en la
   `0.8.0`, salvo cerrar una sola capa de la cima (ver P15).
4. **P9** — merge de la pila; con P15 completa el ciclo de vida.
5. **P12** — conflictos guiados, si los rebases con conflictos siguen siendo habituales.
6. **P44** — una capa suelta cuya rama desaparece dentro de una pila activa; después de P15.

Fuera del orden, pero no descartadas: **P10 + P29** (el doble clic ya cubre lo básico), **P4, P40, P41 y P42** (van con
la `1.0.0` y el Marketplace) y **P1**, que sigue siendo
condición para la `1.0.0` (ver P1).

---

## Ronda del 2026-09-30 · Validar y endurecer

Lo que falta para fiarse de la `0.1.0` tal y como está.

### P1 · Probar con PRs reales, de principio a fin ⏸️

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

Desde entonces, la `0.4.0`, la `0.5.0` y la `0.6.0` salieron de usar el plugin con PRs
reales, así que parte de la lista ya se ha visto funcionar. Sale del orden sugerido, pero
la `1.0.0` sigue esperando a la lista escrita: lo que falta está en *Pendiente de probar*
del README.

### P2 · Tests de flujo con un `gh` falso ✅ sin versión

Hoy hay 63 tests de funciones puras (JSON, URLs, comandos, los planes de la `0.2.0`, el
ajuste de texto de la `0.2.1`, las pilas locales de la `0.3.0`, la publicación de la
`0.4.0`, el restack de la `0.5.0` y la ayuda de la `0.6.0`). `StackService` —estados,
reintento con `--remote`, avisos por código de salida, una escritura a la vez— no tiene
ninguno.

Vuelve al orden (2026-09-30), acotada: antes de P15 y P9, que cierran PRs, borran ramas y
fusionan, hacen falta al menos los tests de esos flujos y de los avisos por código de
salida. `GhCli.locate()` ya da prioridad a la ruta de los ajustes, así que un script
que apunte los argumentos y conteste con salidas grabadas (códigos 2, 3, 6, 8, 9) sirve
de `gh` sin red.

Primera entrega: tests de `StackService` sobre ese script con el framework de pruebas de
la plataforma (`testFramework(TestFrameworkType.Platform)`; son unas librerías, no otro
IDE). Casos mínimos: los estados de la ventana, el reintento tras *multiple remotes*, el
aviso de conflicto con y sin `gh-stack-rebase-state`, y que una segunda escritura espere.

Hecha (2026-09-30), sin subir versión: no cambia nada que vea el usuario. 32 tests en
`src/test/.../flow` (99 en total), cada uno de unos 1 s:

- `FakeGh`: un script de bash que apunta los argumentos de cada llamada y contesta por
  coincidencia exacta o de prefijo, las primeras *n* veces o reteniendo la llamada hasta que
  el test la suelte. El repositorio es git real en un directorio temporal, con dos remotos.
- `StackStateFlowTest`: cargada con detalles, detalles que fallan, códigos 2 y 6, extensión
  que falta, cualquier otro fallo y rebase parado.
- `StackServiceFlowTest`: *multiple remotes* (se elige, se reintenta con `--remote` y se
  recuerda; cancelado, no se reintenta; un remoto recordado que ya no existe no se pasa),
  código 3 con y sin rebase parado (y *Abort Rebase* desde el aviso), 8, 9, 10 y cualquier
  otro con la salida escapada, corte de la secuencia con su `cleanup`, `onFailure` que se
  queda el aviso, una escritura a la vez, rebase → *Push Stack*, y rerere sí/no/cancelar.
- `GitHubWritesFlowTest`: publicar listo (confirmado, como drafts o cancelado), ready/draft
  sin `--remote`, cambiar la base, mover/quitar la label final y crearla si no existe.

Lo que enseñó:

- Una segunda escritura **no espera**: no arranca y avisa *Wait until … finishes*. El test
  comprueba eso, que es lo que quiere el plugin.
- Los avisos de fin salen antes de soltar la escritura (ver C13).
- Contra la IDEA unificada (IU), abrir un proyecto en tests registra un error de una
  actividad de inicio del módulo `ultimate` (la licencia). `FlowTestCase` ignora solo los
  errores de ese módulo.
- `DialogWrapper.showAndGet()` falla en el IDE headless de los tests antes de que un
  `UiInterceptors` pueda contestar: `RemoteChooser` usa `show()` + `isOK`, que en un diálogo
  modal es lo mismo.
- La pregunta de rerere depende también de la configuración global de git: si ahí ya está
  contestada, esos tres tests se saltan (`assume`).

Pendiente para P15 y P9: sus tests van en `GitHubWritesFlowTest` con el mismo `FakeGh`.

### P3 · Una rama que está en dos pilas ✅ `0.3.0`

Desde que la base puede ser cualquier rama, una capa de la pila A puede ser la base de la
pila B. Estando en esa rama, `gh stack view --json` sale con código 6 (*belongs to
multiple stacks*) y `StackService.refreshNow` lo trata igual que el 2: la ventana dice
«no es parte de una pila», que es falso. Comprobado con gh-stack v0.1.1.

Primera entrega: separar el 6 del 2. Con el 6, decir «está en N pilas», listar esas pilas (con el fichero local, ver P7)
y abrir cada una haciendo checkout de su capa superior.

Hecha en la `0.3.0` (estado `InSeveralStacks`). Lo que salió al probarlo con gh-stack real:

- El 6 también sale en la base de varias pilas (`main` con dos pilas encima), no solo en la
  capa que es base de otra. Por eso la lista enseña primero las pilas de la rama y debajo
  el resto de pilas locales, como fuera de una pila.
- «Su capa superior» no sirve si esa capa es la rama compartida: el checkout no cambia
  nada. Se abre desde la capa más alta que no sea base de otra pila, y esa regla vale
  también para la lista de fuera de una pila.
- Una pila de una sola capa con otra pila encima no se puede ver con gh-stack desde
  ninguna rama: la fila lo dice en el tooltip. Verla sin checkout es P28; pedir a gh-stack
  que elija pila, P6.
- *New Stack* desde la rama compartida hace checkout de la base de su pila: `init` desde
  una capa sale con 5 aunque la capa también sea base de otra.

### P4 · Comprobar el entorno e iniciar sesión 🟡

Si falta `gh`, la extensión o la sesión, hoy se ve un error genérico: el de `gh auth` sale
tal cual en el aviso de *details unavailable*. La versión de gh-stack tampoco se mira, y
el plugin depende del JSON y los códigos de salida de la v0.1.1.

Primera entrega: en Settings, un botón *Check* y un resumen con la versión de `gh`,
`gh auth status` por host, `gh stack --version`, si el repositorio admite pilas (código 9)
y los remotos. Si la versión de gh-stack no es conocida, un aviso, no un bloqueo, y *Upgrade* con
`gh extension upgrade gh-stack`. Para iniciar sesión, *Log In* abre una
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
- elegir pila en `view --json` (por número o por su rama superior): en una rama que está en
  varias sale con 6 y no hay forma de ver una pila de una sola capa que es base de otra (P3);
- olvidar una pila local cuyas ramas ya no existen: `unstack --local` solo actúa sobre la
  pila de la rama actual o sobre un número de pila de GitHub, y una pila de un solo PR no
  lo tiene. La `0.2.0` lo resuelve con cinco comandos (`StackPlans.forget`); un
  `gh stack unstack --local <rama>` los sustituiría.

Primera entrega: abrir issues (o PRs pequeños) en `github/gh-stack` con cada caso y el uso
concreto en un IDE. Se apunta aquí qué se aceptó para quitar los rodeos del plugin.

Revisado el 2026-09-30 (gh-stack sigue en la v0.1.1, del 2026-09-02). Varios ya están
pedidos por otros; basta con apoyarlos y contar el caso del IDE:

| Caso                                                   | Issue                                                 |
|--------------------------------------------------------|-------------------------------------------------------|
| Número de la pila en `view --json`                     | [#416](https://github.com/github/gh-stack/issues/416) |
| Elegir pila sin terminal cuando la rama está en varias | [#415](https://github.com/github/gh-stack/issues/415) |
| `view` ignora el número de pila que se le pasa         | [#414](https://github.com/github/gh-stack/issues/414) |
| Insertar una capa en medio sin `unstack` + `link`      | [#382](https://github.com/github/gh-stack/issues/382) |
| Las pilas no se comparten entre worktrees (P43)        | [#459](https://github.com/github/gh-stack/issues/459) |

Quedan por abrir: listar las pilas locales en JSON, `isDraft` en el JSON y
`unstack --local <rama>`.

### P7 · El estado interno de gh-stack, solo como último recurso 🟡

`StackService.localStacks` lee `.git/gh-stack` para listar pilas cuando la rama actual no
está en ninguna y, desde la `0.2.0`, para saber cuáles se quedaron sin ramas y cuál era
el último commit de cada una (`head`), que es lo que permite recuperarlas. Desde la `0.3.0`
también dice en qué pilas está una rama que está en varias (P3). Es estado
interno de gh-stack: se lee solo el esquema 1, sin escribirlo nunca, pero puede cambiar en
cualquier versión.

Primera entrega: aislar esa lectura en una clase con un test por versión conocida de
gh-stack y un aviso en el Log si el esquema es otro. Se sustituye en cuanto P6 consiga un
`gh stack list --json` o P13 lo resuelva con la API.

---

## Ronda del 2026-09-30 · El flujo de la pila

### P8 · Publicar decidiendo PR a PR ✅ `0.4.0`

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

Hecha en la `0.4.0` (`PublishDialog`, `PublishPlans`):

- Una fila por capa activa, con el grafo de la ventana. Las nuevas empiezan como draft,
  como las crea `--auto`; las demás, como están. *All ready* / *All drafts* para todas.
- Título y descripción de las nuevas: con un commit, su asunto y su cuerpo; con varios, el
  asunto del primero y la lista de todos (`git log --reverse --no-merges padre..rama`).
- Los PRs nuevos se nombran por su rama con `--repo` (su URL no existe hasta que acaba el
  submit); los que ya existían, por su URL.
- Un PR cerrado, en la cola de merge o sin datos de GitHub no se toca, y la fila dice por qué.
- Desde el menú de una capa, el diálogo abre con el foco en ella.

### P9 · Merge de la pila 🟡

`gh stack merge <pr> --yes --squash|--merge|--rebase` fusiona de forma atómica hasta la
capa elegida y entra en la cola de merge si la base la usa. No está en el plugin.

Primera entrega: *Merge Up To This Layer…* en el menú de capa. El diálogo muestra qué capas
entran, su review y su CI, y el método (se recuerda el último). Al terminar ofrece
`gh stack sync --prune` para borrar las ramas fusionadas. Nunca sin confirmación.

### P10 · Navegar por la pila 🟡

gh-stack tiene `up`, `down`, `top`, `bottom` y `trunk`, que se saltan las capas
fusionadas. En el plugin solo hay doble clic sobre una capa.

Primera entrega: acciones *Stack Up / Down / Top / Bottom / Base* con el checkout del IDE (el de
`StackService.checkout`, con smart checkout) y la misma regla de saltar las
fusionadas. Sin atajo por defecto, asignables en Keymap, y visibles en *Find Action*, en
el widget de P29 y en el popup de ramas (P30).

### P11 · Rebase parcial y opciones ✅ `0.5.0`

*Rebase Stack* ejecuta `gh stack rebase` entero, con fetch del trunk. Después de tocar una
capa intermedia lo normal es `--upstack` (de la actual a la cima). El aviso *needs
rebase* ofrece también el rebase completo.

Primera entrega: el botón con desplegable: completo, `--upstack`, `--downstack` y
`--no-trunk`. El aviso propone `--upstack` desde la primera capa que lo necesita. La
opción `--committer-date-is-author-date` pasa a los ajustes (P36).

Hecha en la `0.5.0`, después de que un rebase completo tras arreglar una capa intermedia
metiera en cada capa los conflictos con lo nuevo de `main`. Lo que salió al leer
`cmd/rebase.go` y `cmd/utils.go` de gh-stack y probarlo:

- `--upstack` empieza en la rama **actual** (`[branch]` solo elige qué pila cargar) y, si
  es la capa de abajo, la rebasa sobre el trunk después de hacer fetch. Por eso el upstack
  del plugin es `--upstack --no-trunk`: sin fetch, y solo salen los conflictos del cambio.
- Cada capa se rebasa con `git rebase --onto <padre> <base anterior>`: un `--amend` en una
  capa de abajo no reaplica los commits viejos arriba.
- `needsRebase` de la capa de abajo significa «atrás del trunk» y eso no lo arregla un
  upstack sin trunk. La banda separa los dos casos: upstack para las capas atrasadas (desde
  la actual si está a su altura o por debajo; si no, checkout de la primera y vuelta al
  terminar) y rebase completo para el trunk.
- En la barra, *Rebase* es un desplegable: upstack, completo, `--downstack` y `--no-trunk`.
  Cada capa tiene *Rebase Upstack from Here*.
- `--committer-date-is-author-date` sigue pendiente, en P36.

### P12 · Conflictos guiados de principio a fin 🟡

Cuando `gh stack rebase` se para, hoy hay un aviso y una banda con *Resolve Conflicts*, *Continue* y *Abort*. Hay que
pulsar tres veces y no se sabe qué capa se está
rebasando.

Primera entrega: abrir el diálogo de conflictos solo al pararse. Cuando
`ChangeListManager` deje de ver conflictos, ofrecer *Continue* en un clic. La banda
indica qué capa se está rebasando si se puede saber sin leer estado interno de gh-stack (con `REBASE_HEAD` y la rama de
git). *Abort* sigue pidiendo confirmación.

### P13 · Traer una pila de GitHub 🟡

gh-stack usa la API REST `repos/{owner}/{repo}/stacks` (listar, crear, añadir, deshacer y
fusionar). Se comprobó que responde: en un repositorio sin pilas devuelve `[]`. Hoy solo
se puede traer una pila escribiendo su número o un PR.

Primera entrega: *Check Out a Stack…* con la lista de pilas del repositorio desde esa API (número, PRs y autor) y
checkout con `gh stack checkout <número>`. La API está en vista
previa: se lee de forma tolerante, como `StackJson`, y si falla se vuelve al diálogo de
texto.

### P14 · Enlazar PRs que ya existen 🟡

`gh stack link 41 42 43 [--base]` convierte en pila PRs abiertos sin tocar ramas locales,
y `gh stack link <pila> <pr>` añade PRs a una pila. Sirve a quien creó los PRs a mano o
con otra herramienta.

Primera entrega: un diálogo con los PRs abiertos (`gh pr list --json`), elegidos y
ordenados de abajo arriba, con la base opcional. También *Add to Stack…* desde el menú de
Pull Requests, con la misma clave de URL que ya se usa.

### P15 · Cerrar una pila entera: la pila, sus PRs y sus ramas ✅ `0.8.0`

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

**Hecha en la `0.8.0`**, menos cerrar una sola capa de la cima: gh-stack v0.1.1 no saca una
capa de una pila salvo con `gh stack modify` (P16), y cerrar su PR y borrar su rama sin más
deja justo la capa sin rama de P44. Queda para cuando esté P44. Lo que se aprendió al
hacerla, leído de `cmd/unstack.go` de la v0.1.1:

- `gh stack unstack` sin argumento actúa sobre la pila de la rama actual: se lanza con HEAD
  todavía en una capa, y solo después se sale al trunk para borrar las ramas locales.
- Si GitHub deja PRs apilados (en cola o con auto-merge), sale con 0 y **no** deja de seguir
  la pila en local. Por eso el `unstack` va solo y, antes de cerrar o borrar nada, se
  comprueba que la pila ya no esté en `.git/gh-stack`.
- Una pila sin publicar no tiene ID en GitHub: `unstack` avisa y sigue en local. El diálogo
  ya no ofrece la parte de GitHub si ninguna capa tiene PR.
- Las ramas se borran con `git push <remoto> --delete` y `git branch -D` en vez de
  `gh pr close --delete-branch`, que borra las dos a la vez: así cada borrado es una casilla.
  Qué ramas hay en el remoto se pregunta con `git ls-remote` antes del diálogo: con una ref
  remota atrasada (la rama ya se borró al fusionar), el `push --delete` fallaría entero.

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
checkout de la capa, unshelve, commit, `gh stack rebase --upstack` y vuelta, con un único *Undo* si algo falla. Es la
propuesta con más riesgo de esta ronda: primero se prueba
sobre copias, con conflictos incluidos.

### P19 · Commit y restack ✅ `0.5.0`

Al commitear en una capa que no es la cima, las de arriba quedan desfasadas. La ventana
lo muestra (*needs rebase*), pero no lo ofrece en el momento.

Primera entrega: un `CheckinHandlerFactory` (API pública) que, tras commitear en una capa
intermedia, ofrezca *Rebase upstack now / Later / Always*. La opción *Always* queda en los
ajustes (P36).

Hecha en la `0.5.0` (`RestackCheckinHandlerFactory`, `StackService.afterCommit`):

- Tras el commit se vuelve a leer `gh stack view --json`: la pila en pantalla es de antes.
  Solo avisa si alguna capa por encima de la actual quedó atrasada.
- El aviso ofrece *Rebase Upstack* y *Always After Commit*; cerrarlo es *Later*. En los
  ajustes: preguntar, hacerlo sin preguntar o nada.
- *Always* solo rebasa con el árbol limpio (`git status --porcelain --untracked-files=no`):
  git no empieza un rebase con cambios. Si queda algo, avisa en vez de fallar.
- Los commits de la terminal no pasan por aquí; esos los sigue avisando la banda.

### P20 · Nombres para las capas nuevas 🟡

*Add Layer* propone el prefijo de la cima (`feat/`), y `gh stack add -m` genera nombres
con la fecha si no se da ninguno.

Primera entrega: una plantilla en ajustes de proyecto con `{prefix}`, `{n}`, `{slug}` (del
mensaje de commit) y `{user}`. Ejemplo: `{user}/{prefix}{n}-{slug}`. El diálogo aplica la
plantilla y deja editar el resultado.

---

## Ronda del 2026-09-30 · El estado de los PRs

### P21 · Base del PR que no coincide con la capa de abajo ✅ `0.7.0`

La consulta GraphQL ya trae `baseRefName` y no se usa. Si la base de un PR no es la capa
de debajo (`StackSnapshot.parentOf`), la pila en GitHub está rota: alguien cambió la base
a mano o un merge la movió. Caso real visto: una pila local con base `main` cuyo PR
apuntaba a `dev`.

Primera entrega: una pastilla *Base mismatch* en la capa, con el detalle en el tooltip, y *Fix* que ejecuta
`gh stack submit --auto` (actualiza las bases). Sale casi gratis.

Corregido al revisar (2026-09-30): `submit` solo cambia la base de un PR que no está en una
pila de GitHub. En `cmd/submit.go` de la v0.1.1, `ensurePR` solo avisa (*cannot update while
stacked*) si la pila tiene ID, porque la API de pilas gestiona esas bases. Además crearía los
PRs que falten, que no es lo que se pide al arreglar una base.

Hecha en la `0.7.0` (`StackSnapshot.wrongBase`, `ChangePrBaseAction`):

- Pastilla *Wrong base* en la primera línea, junto a *Needs rebase*, y en el tooltip a qué
  rama apunta el PR. Solo PRs abiertos de capas activas que no están en la cola de merge.
  La capa de debajo es la de `parentOf`, que salta las fusionadas: un PR que sigue apuntando
  a una capa fusionada sale marcado.
- *Change Pull Request Base to …*, en el menú de la capa y solo cuando no coincide:
  `gh pr edit <url> --base <capa de debajo>`. Solo ese PR, y el aviso dice qué base tenía.
  El nombre dice hacia dónde va: si la base buena era la del PR, lo que sobra es la pila.
- Sin probar contra un PR de una pila registrada en GitHub: no se sabe si GitHub acepta el
  cambio (P1).

### P22 · Tamaño y conflictos de cada capa ✅ `0.7.0`

La gracia de apilar es que cada PR sea pequeño, pero la ventana no enseña tamaños.
GitHub da `additions`, `deletions`, `changedFiles`, `mergeable` y `mergeStateStatus` en la
misma consulta.

Primera entrega: `+120 −30 · 8 files` en la segunda línea, una pastilla *Conflicts* si
`mergeable` es `CONFLICTING` y *Behind* o *Blocked* según `mergeStateStatus`. Opcional:
un aviso de tamaño a partir de un umbral configurable.

Hecha en la `0.7.0`, con cambios:

- `+120 −30` en verde y rojo en la segunda línea; los ficheros, en el tooltip, que la
  ventana es estrecha.
- *Conflicts* con `mergeable: CONFLICTING` y *Behind* con `mergeStateStatus: BEHIND`. *Blocked* no: sale en casi todo PR
  con review o CI obligatorios y repetía las pastillas de
  review y CI. Mientras GitHub lo calcula (`UNKNOWN`) no se enseña nada.
- El aviso de tamaño por umbral queda pendiente (P38).

### P23 · Revisores y conversaciones 🟡

Hoy solo se ve la decisión global de review. Faltan quién tiene pendiente revisar, quién
aprobó y cuántas conversaciones siguen abiertas.

Primera entrega: `reviewRequests`, `latestReviews` y el número de `reviewThreads` sin
resolver en la consulta. Iniciales de los revisores en la fila y el detalle en el tooltip. *Request Review…* con
`gh pr edit --add-reviewer`, para una capa o para toda la pila.

### P24 · CI con detalle y acciones 🟡

La pastilla de CI solo resume el último commit. Para ver qué falló hay que ir al navegador.

Primera entrega: al pulsar la pastilla, un popup con `gh pr checks <url> --json
name,state,link,workflow`, cada check con su enlace, y *Re-run Failed* con
`gh run rerun <id> --failed`.

### P25 · El diff de una capa contra su base ✅ `0.7.0`

Revisar una capa sola —solo lo que añade sobre la de abajo— es el sentido de apilar. Git4Idea
tiene `GitBrancher.compareAny(rama, otra, repos)` y `showDiff(rama, otra, repos)`
(comprobado por reflexión en la 2026.2.2), que abren la comparación nativa del IDE.

Primera entrega: *Show Layer Changes* en el menú de capa (y Ctrl+doble clic), que compara
la capa con `parentOf(capa)`. Verificar con `verifyPlugin` que esos métodos no llevan
anotaciones internas antes de prometerlo.

Hecha en la `0.7.0` (`ShowLayerChangesAction`, `StackFlows.showLayerChanges`):

- `GitBrancher.showDiff(base, capa, repos)`: pública, sin anotaciones (lo único deprecado de
  `GitBrancher` es un `merge`), y `verifyPlugin` limpio. Compara los dos árboles (`git diff A B`), no desde el commit
  común.
- Por eso, si la capa necesita rebase (la de debajo avanzó), la base es
  `git merge-base abajo capa`: lo mismo que `git diff abajo...capa` y que enseña el PR. Si
  no, la rama de debajo tal cual, que da el mismo diff con un título legible.
- Sin Ctrl+doble clic: en macOS Ctrl+clic es el clic derecho.

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

Primera entrega: *New Stack Based on This Branch* y *Add Layer* en el popup de ramas; *Continue* y *Abort gh stack
rebase* junto a las acciones de rebase en curso. Antes hay
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

Primera entrega: un ajuste por proyecto: *Current branch* (hoy), *Repository default*, *Always:* `<rama>`, o *Last
used*. El diálogo lo usa como valor inicial y sigue dejando
elegir cualquier otra.

### P35 · Ajustes de publicación 🟡

Primera entrega, con ámbito de proyecto:

- modo por defecto: drafts o listos, y si se confirma antes de marcar listos;
- labels que se añaden a todo PR nuevo de una pila (por ejemplo `stacked`);
- revisores y asignados por defecto (`@me` incluido);
- la label final: única por pila (hoy es fija), moverla a la capa nueva al añadir una, o
  ponerla sola en la cima al publicar.

### P36 · `--committer-date-is-author-date` al rebasar 🟡

Era «Ajustes de sync y rebase»: `--prune` al sincronizar, `--committer-date-is-author-date`
al rebasar, el alcance por defecto del rebase (P11) y *Always* para el restack tras commit (P19).

Revisada el 2026-09-30. *Always* ya está desde la `0.5.0` (*After a commit on a lower
layer*: preguntar, rebasar sin preguntar o nada). El alcance por defecto ya no hace falta:
el desplegable pone el upstack primero. `--prune` pasa a P9 y P15, que lo ofrecen justo
cuando hay ramas fusionadas o cerradas que borrar. Queda solo la casilla de
`--committer-date-is-author-date`.

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
ven las pilas del checkout principal, ni al revés.

Revisada el 2026-09-30: el plugin ya lee el mismo fichero que gh-stack. `StackService.gitDir`
sigue la línea `gitdir:` del fichero `.git` de un worktree desde el primer commit, así que
la lista enseña lo mismo que `gh stack` en ese worktree. Compartir pilas entre worktrees ya
está pedido en gh-stack ([#459](https://github.com/github/gh-stack/issues/459), ver P6).

Queda una entrega pequeña: comprobar init, add y submit desde un worktree y explicarlo en
el README.

### P44 · Una capa cuya rama desaparece dentro de una pila activa 🟡

La `0.2.0` resuelve la pila a la que no le queda ninguna rama. Queda el caso a medias:
se cierra el PR de una capa y se borra su rama, pero las demás siguen. No se ha visto
todavía qué devuelve `gh stack view --json` con una capa sin rama, ni si `sync` o
`rebase` la saltan.

Primera entrega: probarlo sobre una copia y pintar esa capa como *Branch deleted*, con *Remove From Stack* (lo que
permita gh-stack, o `modify` en la terminal, P16) y *Restore Branch*, que la recrea en su último commit conocido, igual
que la `0.2.0`.

---

## Ronda del 2026-09-30 (tercera) · Lo que enseñó la `0.4.0`

Salió de arreglar una capa intermedia con la `0.4.0`: el único rebase era el completo, que
trae el trunk, y hubo que resolver en cada capa conflictos que no tenían que ver con el
arreglo. P11 y P19 ya lo proponían; estas tres completan el flujo.

### P45 · Subir las ramas al terminar un rebase ✅ `0.5.0`

Un rebase solo cambia las ramas en local, y los PRs siguen enseñando lo de antes hasta
que se suben. El aviso de fin (también el de *Continue Rebase*) ofrece *Push Stack*:
`gh stack push`, con `--force-with-lease` por rama. No crea PRs; para eso está publicar.

### P46 · La pregunta de git rerere ✅ `0.5.0`

gh-stack pregunta si activar rerere antes de rebasar, pero solo en una terminal
interactiva (`ensureRerere` en `cmd/utils.go`): desde el plugin nunca llegaba a preguntarse.
El primer rebase de cada repositorio hace la misma pregunta y guarda la respuesta en las
mismas claves: `rerere.enabled` y `rerere.autoupdate`, o `gh-stack.rerere-declined`. Así ni
la terminal ni el IDE preguntan lo que se contestó en el otro. Un `rerere.enabled false`
escrito a mano cuenta como respuesta. Cancelar no rebasa, como interrumpir la pregunta en
la terminal.

Con `rerere.autoupdate`, git aplica y añade una resolución recordada pero el rebase se para
igual. *Resolve Conflicts…* sin ficheros en conflicto lo dice y ofrece *Continue Rebase*.

### P47 · Explicar el rebase upstack la primera vez ✅ `0.5.0`

La primera vez que la banda *needs rebase* ofrece el upstack, un *Got it* (`GotItTooltip`,
API pública) explica qué hace y cuándo conviene el rebase completo.

---

## Ronda del 2026-09-30 (cuarta) · Lo que enseñó la `0.5.0`

Salió de usar el menú de una capa: no se sabía qué hacía cada opción ni si actuaba sobre la
capa o sobre la pila, y tras un rebase el *Push Stack* se perdía con la notificación.

### P48 · Qué hace cada opción y qué comando ejecuta ✅ `0.6.0`

Al pasar el ratón, cada opción dice qué hace y el comando tal cual, con sus datos reales (`gh pr ready <url>`,
`git checkout <rama>`, la secuencia de *Forget Stack*). Los comandos
salen de `GhCommands`, los mismos que se ejecutan. Lo que no se sabe hasta ejecutar va en
mayúsculas (`BRANCH`, `MESSAGE`).

- Barra: las acciones implementan `TooltipDescriptionProvider`; sin eso el tooltip solo
  enseña el nombre.
- Menús: `ActionUtil.TOOLTIP_TEXT` en los popups de lista. El clic derecho pasa de
  `JPopupMenu`, que no enseña tooltips, a `createActionGroupPopup`.
- Bandas, casillas y enlaces: `toolTipText`. La lista vacía enseña el comando en gris debajo
  de cada enlace (`StatusText` no tiene tooltip por fragmento).
- Botones de notificaciones y de diálogos sí/no: sin tooltip posible, el comando va en el
  texto.
- La descripción de la acción es texto plano: la barra de estado no entiende HTML.

Queda fuera el menú *Stack* de Pull Requests (un `JPopupMenu` del plugin GitHub): solo
la barra de estado.

### P49 · El siguiente paso, en la ventana ✅ `0.6.0`

Tras un rebase, una banda azul como la de *needs rebase* dice qué capas cambiaron solo en
local y ofrece *Push Stack*. Se va sola cuando las sube un push, sync o publish del plugin,
o cuando cada rama coincide con su remota (push desde la terminal). La notificación sigue.

---

## Cambios pequeños a lo que ya existe (`0.1.x`)

Encontrados al probar y al releer el código. Cada uno cabe en una corrección.

|        | Qué                                                                                                                                                                                                                                                                                   | Dónde                                |
|--------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| ✅ C1  | Las capas sin PR decían lo mismo dos veces: pastilla *No PR* y *Not published yet*. Queda la pastilla `0.7.0`                                                                                                                                                                         | `StackRowRenderer.layerRow`          |
| ✅ C2  | La línea del grafo casi no se veía en tema oscuro: `0x3D444D` pasa a `0x656C76`, el borde enfatizado de GitHub `0.7.0`                                                                                                                                                                | `StackColors`                        |
| ✅ C3  | El progreso de *Check Out Stack* enseñaba la URL entera del PR; ahora `#13` `0.7.0`                                                                                                                                                                                                   | `StackFlows.checkoutFromPullRequest` |
| ✅ C4  | `StackRow.Layer.position` no se usaba: el tooltip dice `layer 2 of 3` `0.7.0`                                                                                                                                                                                                         | `StackRows`                          |
| ✅ C5  | `repo!!` en el aviso de *needs rebase*; no puede fallar hoy, pero sobra: quitado al rehacer la banda `0.5.0`                                                                                                                                                                          | `StackPanel.renderBanners`           |
| ✅ C6  | La página de ajustes estaba en *Tools*; pasa a *Version Control*. En ese grupo solo hay páginas de proyecto, así que es `projectConfigurable`; los ajustes siguen siendo globales `0.7.0`                                                                                             | `plugin.xml`                         |
| ✅ C7  | La caché de build de Gradle devolvía clases de test compiladas contra firmas viejas (fallos falsos, incluso tras `clean`): desactivada `0.2.0`                                                                                                                                        | `gradle.properties`                  |
| ✅ C8  | En el menú de una capa, *Add Layer on Top…* y los dos *Publish* parecían actuar sobre esa capa y actúan sobre toda la pila: ahora dicen *Stack* `0.4.0`. Superada por C11                                                                                                             | `StacklaneBundle`                    |
| ✅ C9  | *Mark Ready*, *Convert to Draft*, *Labels…* y *Mark as Final Layer* no tenían icono `0.4.0`                                                                                                                                                                                           | `Actions`                            |
| ✅ C10 | El clic derecho enseñaba el menú de la fila seleccionada, no el de la fila bajo el ratón; fuera de las filas, igual. Ahora selecciona esa fila, o quita la selección `0.4.0`                                                                                                          | `StackPanel`                         |
| ✅ C12 | Los tooltips medían lo que el comando más largo (casi 900 px con una URL de PR). Ahora, unos 520 px; cada comando en su bloque con fondo, como el código en Markdown, y las URLs en azul. Swing lee los `px` de CSS como puntos (×1,3): el ancho se escribe dividido `0.7.0`          | `Help`, `TooltipStyle`               |
| 🟡 C13 | Los avisos de fin (y sus botones: *Push Stack*, *Abort Rebase*) salen antes de soltar la escritura: entre el aviso y el final de `syncIde`, pulsar uno da *Wait until … finishes*. Lo enseñaron los tests de P2; con la mano casi nunca se llega a tiempo. Avisar después de soltarla | `StackService.execute`               |
| ✅ C11 | Aun diciendo *Stack*, ver *Publish Stack as Drafts* en el menú de una capa sin PR confundía. El menú de una capa solo tiene lo suyo; lo de la pila sale al hacer clic fuera de las filas o sobre la base `0.6.0`                                                                      | `plugin.xml`, `StackPanel`           |
