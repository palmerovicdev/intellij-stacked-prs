# Desarrollo de Upstack

Compilar, probar, versionar y publicar. Qué hace el plugin está en el
[manual](manual.md); la portada, en el [README](../README.md).

## Requisitos

| | |
|---|---|
| IDE mínimo | IntelliJ IDEA 2026.2 (`sinceBuild = 262`), sin cota superior |
| Bytecode | JVM 21 (`jvmTarget` y `options.release`) |
| Toolchain | JDK 25: el JBR de la IDEA instalada (`org.gradle.java.installations.paths`) |
| Kotlin | 2.4.20 |
| IntelliJ Platform Gradle Plugin | 2.18.1 |
| Gradle | 9.5.0 vía wrapper |

Gradle, el IntelliJ Platform Gradle Plugin y Kotlin son las mismas versiones que ya usa
Tasklane.

## Cómo se resuelve la plataforma

`localIdePath` en `gradle.properties` decide contra qué se compila, se ejecuta y se verifica:

| | |
|---|---|
| **Con valor** (por defecto) | La IDEA ya instalada en `/Applications/IntelliJ IDEA.app` (2026.2.2, build 262.10315.125) y su JBR 25. **Descarga cero.** |
| **Vacío** (`-PlocalIdePath=`) | Descarga `platformType` y `platformVersion`: la IU 2026.2.2, la misma versión que la instalada. Es lo que hace CI, y solo entonces se indexan las opciones de Settings (`buildSearchableOptions`). |

Si la IDEA está en otro sitio (por ejemplo, en `~/Applications`), se pasan las dos rutas:

```bash
./gradlew runIde "-PlocalIdePath=$HOME/Applications/IntelliJ IDEA.app" \
  "-Porg.gradle.java.installations.paths=$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
```

En CI no hay IDEA instalada: `setup-java` pone un JDK 25, Gradle corre sobre él y lo usa como
toolchain, porque la ruta de `org.gradle.java.installations.paths` no existe allí.

## Comandos

```bash
./gradlew test                  # tests unitarios y de flujo (con un gh falso, sin red)
./gradlew verifyPlugin          # Plugin Verifier estricto contra la IDEA local
./gradlew runIde -PrunIdeProject=/ruta/a/un/repo   # IDE de prueba con ese proyecto
./gradlew buildPlugin           # build/distributions/upstack-<versión>.zip
```

Para probarlo en el IDE de trabajo: *Settings → Plugins → ⚙ → Install Plugin from Disk…* y
elegir el zip.

[`build.yml`](../.github/workflows/build.yml) pasa los tests en Linux y macOS, y construye y
verifica el plugin en Linux, en cada push a `main` y en cada PR. Windows no está: `FakeGh` es
un script de bash (ver P42 en el [roadmap](roadmap.md)).

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

## Versiones

Versión actual: **0.14.0**. La regla de versiones está en el
[roadmap](roadmap.md#versiones), y qué trae cada una, en el [CHANGELOG](../CHANGELOG.md).

## Publicar

Publicar es un acto deliberado: lo dispara **una etiqueta `vX.Y.Z`**, no un push a `main`.

**Antes de etiquetar**, tres sitios y en este orden:

1. `pluginVersion` en `gradle.properties`
2. `changeNotes` en `build.gradle.kts`: es lo que sale en la ficha del Marketplace y en el
   diálogo de actualización del IDE
3. [`CHANGELOG.md`](../CHANGELOG.md), con su sección `## X.Y.Z — …`: es el texto de la
   release de GitHub

Y la `<description>` de `plugin.xml`, que nadie toca al subir versión: contrastarla con el
CHANGELOG antes de publicar.

**Con el label `release` en un PR**, la forma normal: se suben esos tres sitios en el PR, se
le pone el label y, **al mergearlo**,
[`release-on-label.yml`](../.github/workflows/release-on-label.yml) comprueba que `vX.Y.Z`
no existe todavía y que el CHANGELOG tiene su sección, crea la etiqueta sobre el commit del
merge, lanza `release.yml` sobre ella y lo dice en un comentario del PR. Poner el label a un
PR ya mergeado también vale; ponérselo a uno abierto no hace nada hasta mergearlo.
`release.yml` se lanza a mano (`workflow_dispatch`) porque una etiqueta empujada con el
`GITHUB_TOKEN` no dispara otros workflows. El label tiene que existir en el repositorio:

```bash
gh label create release --description "Publica la versión de pluginVersion al mergear"
```

**A mano**, con todo ya en `main`:

```bash
git tag vX.Y.Z && git push origin vX.Y.Z
```

Las dos acaban en [`release.yml`](../.github/workflows/release.yml), que comprueba que la
etiqueta y `pluginVersion` dicen lo mismo, pasa los tests y el Plugin Verifier estricto
contra la IU 2026.2.2, construye el zip y crea la **release de GitHub** con la sección del
CHANGELOG y el zip colgado. Si están los secretos de firma, cuelga el zip firmado; si además
está `PUBLISH_TOKEN`, lo sube al Marketplace. Sin ellos lo avisa y la release sale igual, con
el zip sin firmar, que es el que se sube a mano en la web del Marketplace.

**Secretos del repositorio.** Los cuatro van como *secrets* de GitHub Actions y no tocan el
repositorio:

| | |
|---|---|
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Firma del plugin. Se generan una vez siguiendo [*Plugin Signing*](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) |
| `PUBLISH_TOKEN` | Token del perfil del Marketplace |

**Una versión con sufijo va a su propio canal:** `0.13.0-beta.1` se publica en `beta`, no en
el estable, y solo la ve quien haya añadido ese canal en el IDE. El canal sale del propio
número de versión, así que no hay un segundo sitio que pueda discrepar.

**La primera versión se sube a mano.** El Marketplace no acepta un `publishPlugin` de un
plugin que todavía no existe: la primera vez se sube el zip desde la web, y a partir de ahí
la release lo publica sola.

## Capturas del Marketplace

Las diez diapositivas de [`docs/marketplace/`](marketplace) son las del README y las del
**carrusel** del Marketplace (*Media*), que se suben a mano desde su panel. El README las
enlaza por su nombre: si se renombra o se borra una, el README se queda sin ella. Cómo se
rehacen y el post de Reddit están en [su README](marketplace/README.md).
