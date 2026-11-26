# Plan: modo de pull requests

## Objetivo y alcance

Incorporar a Upstack una vista propia para crear, listar, revisar y gestionar pull requests, pertenezcan o no a un stack, siguiendo los flujos y las referencias visuales de IntelliJ aportados por el usuario. Las funciones actuales de stacks deben mantenerse.

Los requisitos priorizados el 25 de noviembre de 2026 están integrados en las secciones siguientes. Este documento organiza el alcance; no afirma que cada elemento esté terminado ni establece por sí solo el orden de implementación. El estado y las comprobaciones realizadas se registran en [`odd/tasks/open-pull-requests.md`](../odd/tasks/open-pull-requests.md).

## 1. Lista de pull requests

- [ ] Mostrar los PR del repositorio seleccionado, pertenezcan o no a un stack, con paginación y sin limitar los resultados a la primera página.
- [ ] Mantener disponible la lista al cerrar y volver a abrir la tool window, evitando mostrarla vacía mientras se actualiza.
- [ ] Incorporar búsqueda y filtros como en IntelliJ: estado, autor, label, assignee, review y orden.
- [ ] Mostrar número, título, ramas de origen y base, draft/ready, etiquetas, revisión, checks y tamaño del cambio cuando estén disponibles. Señalar conflictos, rama atrasada, checks fallidos y cambios solicitados.
- [ ] Adaptar a los PR el detalle emergente y el resumen superior de stacks. Ofrecer refresco manual y automático, con estados diferenciados de carga, lista vacía y error.
- [ ] Abrir el detalle dedicado del PR al hacer doble clic en una fila.

## 2. Crear y abrir un pull request

Reproducir el flujo **New Pull Request** de IntelliJ, con vista previa de cambios y posibilidad de cancelar y volver a la lista:

- [ ] Elegir cualquier rama local o remota como base y seleccionar la rama de origen que se integrará en ella.
- [ ] Seleccionar reviewers, labels, assignees y tags mediante campos con búsqueda/autocompletado y listas seleccionables.
- [ ] Escribir título y descripción con soporte Markdown, incluidas imágenes.
- [ ] Mostrar arriba los archivos cambiados y abrir el diff del IDE al hacer doble clic en uno.
- [ ] Contemplar la creación como draft o ready y reutilizar, cuando corresponda, el editor y las plantillas existentes. La publicación actual usa `gh stack submit` y requiere una adaptación para PR individuales.

## 3. Detalle y revisión del PR

Al abrir un PR desde la lista, mostrar una vista dedicada siguiendo la referencia de IntelliJ:

- [ ] Mostrar el título con edición en el mismo lugar mediante un botón, sin abrir un diálogo aparte.
- [ ] Mostrar `base <- rama de origen` en lugar de «View Timeline»; permitir cambiar la base y copiar el nombre de la rama.
- [ ] Mostrar los archivos cambiados. Solo los que tengan comentarios llevan un icono de conversación y su número; el doble clic abre el diff de ese archivo.
- [ ] Mostrar en el diff los comentarios de revisión en el archivo y las líneas correspondientes, con sus respuestas y su estado resuelto o pendiente. Investigar primero la reutilización del visor de revisión de IntelliJ; si no es viable, evaluar una alternativa que conserve los comentarios.
- [ ] Conservar los comentarios sobre versiones anteriores y los que no puedan situarse en una línea; no descartarlos silenciosamente.
- [ ] Adaptar la visualización de cambios y el checkout a PR sin rama local y a PR procedentes de forks.
- [ ] Retirar el visor independiente **Changes and Review Comments** actualmente agregado: la revisión debe ocurrir desde la vista del PR.

## 4. Acciones desde la lista y el detalle

Las siguientes acciones deben estar disponibles desde ambas vistas:

- [ ] Hacer merge individual y cerrar el PR con confirmación; decidir si el cierre incluye comentario y eliminación de ramas como opciones.
- [ ] Cambiar entre draft y ready y elegir una nueva rama base, sin deducirla de la capa inferior de un stack.
- [ ] Solicitar revisión y asignar personas.
- [ ] Consultar el estado de los checks y abrir sus detalles cuando fallen. Contemplar logs y la repetición de checks fallidos cuando sean de GitHub Actions.

Además, conservar o adaptar las acciones individuales aplicables:

- [ ] Editar título y descripción con sus valores actuales; en el detalle, la edición del título debe ser en el mismo lugar. Evaluar plantillas y edición mediante Markdown cuando no exista rama local.
- [ ] Ver y editar labels, abrir el PR en GitHub y copiar su enlace.

El merge de stacks existente es una operación de pila: no debe reutilizarse como si fuera un merge individual. Rebase upstack/downstack, merge hasta una capa, sincronización de pilas y etiqueta de capa final tampoco deben trasladarse sin una decisión específica sobre su equivalente para PR individuales.

## Decisiones y cuestiones pendientes

- Precisar el alcance de repositorios y remotos, y cómo se relaciona la lista inicialmente centrada en PR abiertos con el filtro por estado.
- Aclarar qué son los **tags** solicitados en el formulario y cómo se diferencian de los labels.
- Decidir si los comentarios serán solo de lectura o si también se podrán escribir, responder y resolver desde el IDE.
- Confirmar qué debe ocurrir si el plugin GitHub de IntelliJ no está disponible o no se puede integrar su visualizador.
- Definir los métodos y restricciones aplicables al merge individual y las opciones de cierre indicadas arriba.
- Acordar el orden de implementación y revisar el estado real de cada capacidad antes de marcarla como completada.

## Comprobaciones

- [ ] Cubrir listado completo, paginación, búsqueda, filtros, estados, actualización y conservación de la lista al reabrir.
- [ ] Comprobar que creación y acciones actúan sobre el PR y repositorio correctos, incluidos forks y PR sin rama local.
- [ ] Verificar en el IDE los recorridos de creación, lista, detalle, archivos y conversaciones: posición, respuestas, estado y comentarios desactualizados o sin ubicación.
- [ ] Probar errores de red, permisos y datos no disponibles sin ocultar información ni presentar acciones engañosas.
- [ ] Mantener los flujos existentes de stacks y distinguir las pruebas automatizadas de la comprobación visual y funcional en el IDE.

## Referencias

Las capturas aportadas por el usuario son la referencia para los flujos y su presentación; el documento las identifica por número y no contiene copias locales:

- **New Pull Request y archivos cambiados:** `Image #1`, `Image #5`.
- **Labels, reviewers y assignees:** `Image #2`, `Image #3`, `Image #4`.
- **Detalle, título editable, ramas, diff y acciones:** `Image #6`–`Image #12`.
- **Lista, búsqueda, filtros, orden y selección:** `Image #13`–`Image #20`.

- [`StackFlows.kt`](../src/main/kotlin/com/upstack/actions/StackFlows.kt): edición de PR y diff de capas.
- [`PrTarget.kt`](../src/main/kotlin/com/upstack/actions/PrTarget.kt): selección de PR desde stacks o la ventana nativa.
- [`upstack-github.xml`](../src/main/resources/META-INF/upstack-github.xml): acciones integradas en la ventana nativa.
- [`README.md`](../README.md): capacidades de stacks.
