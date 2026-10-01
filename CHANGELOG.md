# Changelog

Cada versión, con lo que trae. La regla de versiones está en
[docs/roadmap.md](docs/roadmap.md#versiones).

## 0.8.0 — Cerrar una pila entera

Salió del roadmap (P15): cerrar a mano los PRs y borrar las ramas antes de deshacer la pila
dejó la pila huérfana que motivó la `0.2.0`.

- **Close Stack…**, en la barra y en el clic derecho fuera de las filas: deshace la pila,
  cierra sus PRs abiertos y borra sus ramas. El diálogo tiene una casilla por paso
  —deshacerla también en GitHub, cerrar los PRs (con comentario opcional), borrar las ramas
  del remoto, borrar las locales—, debajo de cada una los PRs y ramas que toca, y los
  comandos en el orden en que se ejecutan.
- **Orden seguro**: `gh stack unstack` primero, con las ramas aún vivas; después
  `gh pr close` de la cima hacia abajo, `git push <remoto> --delete` y `git branch -D`. Si
  estás en una de las ramas, antes se cambia al trunk.
- Si GitHub deja la pila apilada (PRs en cola o con auto-merge), gh-stack sale bien pero la
  sigue registrando: el plugin lo comprueba y para ahí, sin cerrar ni borrar nada, y lo dice.
- Las ramas remotas solo se borran si antes se cierran los PRs abiertos: borrar la rama de
  un PR abierto lo cierra sin comentario. Nunca se borra una rama que es la base de otra
  pila.
- Antes del diálogo se pregunta al remoto qué ramas tiene (`git ls-remote`). Las ramas
  locales que no coinciden con el remoto se avisan y, por defecto, no se borran.
- Todo queda en el Log; el tooltip de *Close Stack…* enseña los comandos con los datos de la
  pila.

## 0.7.0 — Cada capa, de un vistazo

Salió de revisar el roadmap tras la `0.6.0`: la consulta a GitHub ya traía la base de cada
PR y no se usaba, y faltaba ver el diff de una capa sola, que es el sentido de apilar.

- **Show Layer Changes**, en el menú de una capa: el diff del IDE con lo que añade la capa
  sobre la de debajo, lo mismo que enseña su PR. Si la de debajo avanzó después, compara
  desde su último commit común (como `git diff abajo...capa`), así lo nuevo de abajo no sale
  como si la capa lo quitara.
- **Wrong base**: si el PR de una capa apunta en GitHub a otra rama que la de debajo (se
  cambió a mano, o se fusionó la de debajo sin borrar su rama), la fila lo marca y el
  tooltip dice a cuál apunta. *Change Pull Request Base to …* se la cambia con
  `gh pr edit <url> --base <rama>`: solo ese PR, y el aviso dice cuál tenía.
- **Tamaño y conflictos**: `+120 −30` en cada capa con PR (los ficheros, en el tooltip),
  *Conflicts* si GitHub no puede fusionarla sin conflictos y *Behind* si la protección de su
  base exige ponerla al día.
- **Tooltips más estrechos** (unos 520 px): cada comando va en su bloque con fondo, como el
  código en Markdown, y las URLs en azul, como enlaces.
- El tooltip de una capa dice cuál es (`layer 2 of 3`).
- Las capas sin PR ya no dicen dos veces que no están publicadas: queda la pastilla *No PR*.
- La línea del grafo se ve en tema oscuro.
- *Check Out Stack Locally*, desde Pull Requests, enseña `#13` en el progreso y no la URL.
- Los ajustes pasan a *Settings → Version Control → Stacklane*, junto a Git y GitHub.

## 0.6.0 — Qué hace cada opción y qué es lo siguiente

Salió de usar el menú de una capa: no se sabía qué hacía cada opción ni si actuaba sobre la
capa o sobre toda la pila, y tras un rebase el *Push Stack* se perdía con la notificación.

- **Cada opción explica qué hace y el comando que ejecuta** al pasar el ratón: barra,
  desplegable *Rebase*, menú de clic derecho, botones de las bandas y opciones de los
  diálogos. El comando lleva los datos reales (`gh pr ready <url>`, `git checkout <rama>`) y
  sale de los mismos `GhCommands` que se ejecutan. En la barra, el tooltip enseñaba solo el
  nombre.
- La lista vacía enseña en gris, debajo de cada enlace, el comando que ejecuta.
- Los botones de notificaciones y de diálogos sí/no no admiten tooltip: el comando va en el
  texto (rerere, *Forget/Recreate*, *Publish Ready*, conflictos, label final que falta).
- **El menú de una capa solo tiene lo de esa capa**, sin *Stack* en el nombre. *Add Layer on
  Top of Stack…* y los tres *Publish Stack* salen al hacer clic derecho fuera de las filas o
  sobre la base, y en la barra. gh-stack no publica una capa suelta.
- **Tras un rebase, una banda azul ofrece *Push Stack*** con las capas que cambiaron solo en
  local. Se va sola al subirlas (push, sync o publish) o cuando cada rama coincide con su
  remota, también tras un push desde la terminal.
- El clic derecho es un menú de lista (hace falta para los tooltips). En el menú *Stack* de
  Pull Requests, que es del plugin GitHub, la explicación solo sale en la barra de estado.

## 0.5.0 — Llevar un cambio hacia arriba sin traer el trunk

Salió de usar el plugin: tras arreglar algo en una capa intermedia, *Rebase Stack* ejecutaba
`gh stack rebase` entero. Eso trae el trunk y rebasa la capa de abajo sobre él, así que cada
capa se comía también los conflictos con lo nuevo de `main`, no solo los del arreglo.

- **Rebase Upstack** (`gh stack rebase --upstack --no-trunk`): lleva los commits de una capa
  a las de encima, sin fetch y sin tocar el trunk. Solo salen los conflictos que causa ese
  cambio. Sin `--no-trunk`, desde la capa de abajo gh-stack rebasaría también sobre el trunk.
- **Tras un commit del IDE** en una capa que no es la cima, un aviso ofrece el rebase
  upstack o *Always After Commit*. En *Settings → Tools → Stacklane* se elige: preguntar,
  hacerlo sin preguntar (solo con el árbol limpio; si no, avisa) o nada.
- **La banda *needs rebase*** separa los dos casos. Si hay capas que ya no parten de la de
  debajo, propone el upstack: desde la capa actual si está a su altura o por debajo; si no,
  hace checkout de la primera atrasada y vuelve al terminar. Si la capa de abajo se quedó
  atrás del trunk, eso solo lo arregla *Rebase Whole Stack onto main*. La primera vez, un
  *Got it* explica la diferencia.
- **Rebase** en la barra es un desplegable: upstack desde la capa actual, pila entera,
  downstack (`--downstack`) y capas entre sí (`--no-trunk`). Cada capa tiene *Rebase
  Upstack from Here*.
- **Push al terminar**: un rebase cambia las ramas solo en local. El aviso de fin, también
  el de *Continue Rebase*, ofrece *Push Stack* (`gh stack push`).
- **git rerere**: gh-stack pregunta si activarlo antes de rebasar, pero solo en una terminal
  interactiva, así que desde el plugin nunca llegaba a preguntarse. El primer rebase de cada
  repositorio hace la misma pregunta y guarda la respuesta en las mismas claves
  (`rerere.enabled` y `rerere.autoupdate`, o `gh-stack.rerere-declined`): ni la terminal ni
  el IDE vuelven a preguntar.
- *Resolve Conflicts…* sin ficheros en conflicto (rerere ya aplicó una resolución recordada)
  ofrece *Continue Rebase* en el mismo aviso.

## 0.4.0 — Publicar decidiendo PR a PR

`gh stack submit --auto` crea los PRs nuevos como draft con títulos generados, y `--open`
marca listos todos los de la pila, también los drafts que ya existían. Para dejar listo uno
solo había que publicar como draft y marcarlo después.

- **Publish Stack…**: una fila por capa, con el grafo de la ventana. En cada una, si queda
  lista para review o como draft; en las nuevas, además, el título y la descripción del PR,
  propuestos a partir de sus commits. Se ejecuta `gh stack submit --auto` y después un
  `gh pr edit` por PR nuevo y un `gh pr ready` (o `--undo`) por cada cambio. La vista previa
  y el Log enseñan la secuencia entera.
- Las acciones de toda la pila dicen *Stack*: *Add Layer on Top of Stack…*, *Publish Stack
  as Drafts* y *Publish Stack Ready for Review…*. En el menú de una capa parecía que solo
  actuaban sobre ella.
- Todas las acciones del menú de una capa tienen icono, también en el menú *Stack* de la
  ventana Pull Requests.
- El clic derecho abre el menú de la fila que está bajo el ratón (la selecciona) y, fuera
  de las filas, el de la pila (quita la selección). Antes enseñaba el de la fila que
  estuviera seleccionada.

## 0.3.0 — Una rama que está en dos pilas

Desde que la base puede ser cualquier rama, una capa de una pila puede ser la base de otra.
En esa rama `gh stack view --json` sale con código 6 (*belongs to multiple stacks*) y la
ventana decía que no estaba en ninguna pila. Pasa lo mismo en la base de varias pilas, como
`main` con dos pilas encima.

- La ventana dice en cuántas pilas está la rama y las lista primero, con nodo relleno y
  *HEAD*. El tooltip dice si la rama es su base o qué capa es. Debajo siguen las demás pilas
  locales.
- Abrir una la saca desde su capa más alta que no sea también base de otra pila, que es
  desde donde gh-stack la puede enseñar. Si no le queda ninguna (una pila de una sola capa
  con otra pila encima), el tooltip lo explica.
- La lista de pilas fuera de una pila aplica la misma regla: antes, abrir una pila cuya cima
  era base de otra llevaba a una rama en la que gh-stack no enseñaba nada.
- *New Stack* desde una rama así hace antes checkout de la base de su pila, como desde
  cualquier otra capa: gh-stack no deja empezar una pila desde una capa (código 5).

## 0.2.1 — Textos que se ajustan al ancho del diálogo

- *New Stack*: el aviso de que estás en una capa de otra pila y la vista previa de
  comandos ya no ensanchan el diálogo. Se parten en líneas a su ancho y crecen hacia abajo.
- La vista previa de comandos hace lo mismo en *Add Layer* y *Labels*. Se sigue pudiendo
  seleccionar y copiar, y no es una parada de Tab.

## 0.2.0 — Pilas cuyas ramas ya no existen

Salió de usar el plugin en un repositorio real. Se cerró un PR y se borró su rama, pero
gh-stack siguió registrando la pila: no se podía sacar (`pathspec did not match`) ni
reutilizar sus nombres en una pila nueva (`already exists in a stack`).

- Las pilas locales se muestran con lo que queda de sus ramas. Si no queda ninguna, se
  marcan *Branches deleted*; si solo están en el remoto, *Only on remote*, y se traen con
  `gh stack checkout`. Si queda alguna en local, se usa el checkout del IDE.
- **Forget Stack** deja de seguir en local una pila sin ramas. GitHub y el árbol de trabajo
  no se tocan (ver *Decisiones* en el README).
- **Recreate on Another Base…** la olvida y abre *New Stack* con las mismas capas: se elige
  la base y se recuperan las ramas en su último commit conocido.
- *New Stack*:
  - Si una capa lleva el nombre que retiene una pila sin ramas, la olvida antes y ofrece
    recuperar la rama.
  - Si la retiene una pila activa, lo dice antes de ejecutar nada.
  - La secuencia completa se ve en la vista previa, un comando por línea.
- *New Stack* enseña la pila resultante como grafo (capas nuevas y adoptadas, y la base) y
  agrupa las bases en locales y solo remotas.
- Si una secuencia se corta a medias, se deshace exactamente lo que hizo (`Plan.cleanup`).
- Corregido: un remoto con alias SSH (`git@github-personal:org/repo`) ahora se empareja con
  sus PRs de `github.com`.
- Build: desactivada la caché de build de Gradle, que devolvía clases de test compiladas
  contra firmas viejas.

## 0.1.0 — Primera versión

- Ventana Stacks: la pila como grafo desde la capa superior hasta la base, con PR, draft o
  listo, labels, review, CI, HEAD y *needs rebase*.
- Nueva pila sobre cualquier rama, añadir capa, publicar como drafts o listos para revisión,
  sync y rebase con conflictos.
- Listo/draft, labels y `stack-final` desde la pila y desde los menús de la ventana Pull
  Requests, sin API interna.
- Pestaña Log y el comando exacto en cada diálogo.
