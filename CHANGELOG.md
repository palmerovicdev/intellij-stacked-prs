# Changelog

Cada versión, con lo que trae. La regla de versiones está en
[docs/roadmap.md](docs/roadmap.md#versiones).

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
