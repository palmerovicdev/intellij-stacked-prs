# Changelog

Cada versión, con lo que trae. La regla de versiones está en
[docs/roadmap.md](docs/roadmap.md#versiones).

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
