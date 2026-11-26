# Plan: modo de pull requests abiertos

## Objetivo

Incorporar a Upstack un modo para listar y gestionar los pull requests abiertos, pertenezcan o no a un stack. El usuario podrá revisar el listado y decidir qué capacidades se implementan y en qué orden.

Este documento es un plan, no una autorización para implementar todas las opciones enumeradas. Los comentarios de revisión en el visualizador de cambios son un requisito destacado por el usuario.

## Punto de partida

- La ventana de stacks ya muestra información y acciones de PR que pueden servir de base.
- El menú **Stack** de la ventana nativa **Pull Requests** de IntelliJ ya ofrece algunas acciones, pero no constituye una lista propia de Upstack.
- Al editar un PR desde esa ventana, actualmente se lee su descripción, pero el título no se precarga.
- **Show Layer Changes** abre un diff entre referencias Git; no le proporciona el contexto del PR ni de sus conversaciones de revisión.

## Funcionalidades candidatas

### 1. Lista y estados

- [ ] Añadir un modo para listar todos los PR abiertos del repositorio seleccionado, sean o no parte de un stack.
- [ ] Contemplar la paginación para no limitar la lista a la primera página.
- [ ] Mostrar número, título, ramas de origen y base, draft/ready, etiquetas, revisión, checks y tamaño del cambio cuando estén disponibles.
- [ ] Señalar conflictos, rama atrasada respecto a su base, checks fallidos y cambios solicitados.
- [ ] Adaptar el detalle emergente y el resumen superior de stacks a una lista de PR.
- [ ] Ofrecer refresco manual y automático y contemplar carga, lista vacía y errores.

### 2. Revisión de cambios y comentarios

- [ ] Abrir los cambios de un PR y mostrar los comentarios de revisión en el archivo y las líneas correspondientes, con sus respuestas y su estado resuelto o pendiente.
- [ ] Investigar primero si es viable reutilizar el visualizador de revisión de PR de IntelliJ. Si no lo es, evaluar cómo integrar los comentarios en un diff sin dar por hecha ninguna solución.
- [ ] Contemplar comentarios sobre versiones anteriores del diff y comentarios cuya línea ya no pueda situarse; no descartarlos silenciosamente.
- [ ] Adaptar la visualización de cambios y el checkout para PR sin rama local y PR procedentes de forks.

### 3. Acciones individuales

- [ ] Editar título y descripción reutilizando el diálogo existente y precargando ambos valores actuales.
- [ ] Evaluar el uso de las plantillas y de la edición mediante Markdown cuando no exista una rama local.
- [ ] Cambiar entre draft y ready.
- [ ] Ver y editar etiquetas.
- [ ] Abrir el PR en GitHub y copiar su enlace.
- [ ] Ver los checks, sus detalles y logs; ofrecer la repetición de fallidos cuando sean de GitHub Actions.
- [ ] Permitir elegir una nueva rama base, en lugar de deducirla de la capa inferior de un stack.
- [ ] Cerrar un PR con confirmación; decidir si se incluyen comentario y eliminación de ramas como opciones.
- [ ] Estudiar un flujo de merge individual con los métodos y restricciones aplicables. El merge de stacks existente es atómico para la pila y no debe reutilizarse como si fuera un merge individual.

### 4. Creación de PR

- [ ] Evaluar la creación de un PR sencillo con título, descripción, rama de origen, base y estado draft/ready.
- [ ] Reutilizar, donde corresponda, el editor y las plantillas de publicación. La creación actual utiliza `gh stack submit` y requiere una adaptación para PR individuales.

## Límites y decisiones pendientes

- Acordar qué funcionalidades candidatas se incorporan y su orden; la lista, la edición de título y descripción y la revisión de comentarios son posibles puntos iniciales, no una secuencia cerrada.
- Precisar el alcance del repositorio y de los remotos que mostrará el modo.
- Decidir si los comentarios serán solo de lectura o si también se podrán escribir, responder y resolver desde el IDE.
- Confirmar qué debe ocurrir si el plugin GitHub de IntelliJ no está disponible o si no se puede integrar su visualizador.
- No copiar sin adaptar operaciones propias de stacks: rebase upstack/downstack, merge hasta una capa, sincronización de pilas y etiqueta de capa final. Sus posibles equivalentes para PR individuales requieren una decisión específica.

## Comprobaciones para la implementación

- [ ] Cubrir con pruebas el listado completo, la paginación, los estados y la actualización.
- [ ] Comprobar que las acciones actúan sobre el PR y repositorio correctos, incluidos forks y PR sin rama local.
- [ ] Verificar en el IDE el recorrido real de cambios y comentarios: posición, conversaciones, estados y comentarios desactualizados.
- [ ] Probar errores de red, permisos y datos no disponibles sin ocultar información ni presentar acciones engañosas.
- [ ] Mantener los flujos existentes de stacks y distinguir las verificaciones automatizadas de las pruebas visuales en el IDE.

## Referencias

- [`StackFlows.kt`](../src/main/kotlin/com/upstack/actions/StackFlows.kt): edición de PR y diff de capas.
- [`PrTarget.kt`](../src/main/kotlin/com/upstack/actions/PrTarget.kt): selección de PR desde stacks o la ventana nativa.
- [`upstack-github.xml`](../src/main/resources/META-INF/upstack-github.xml): acciones integradas en la ventana nativa.
- [`README.md`](../README.md): capacidades actuales de stacks.

---

## Requisitos nuevos del usuario — 25 de noviembre de 2026

Este bloque registra el alcance que el usuario prioriza ahora. Mañana se revisará nuevamente antes de convertirlo en tareas de implementación. Las capturas se conservan como referencias visuales; no implican que las funcionalidades estén implementadas.

### Crear y abrir un pull request

Reproducir el flujo de **New Pull Request** de IntelliJ, con vista previa de cambios y posibilidad de cancelar y volver a la lista de PRs. El formulario debe permitir:

- Elegir cualquier rama local o remota como base.
- Elegir la rama de origen que se integrará en la base.
- Seleccionar reviewers, labels, assignees y tags mediante campos con búsqueda/autocompletado y listas seleccionables.
- Escribir el título y un body con soporte Markdown, incluidas imágenes.
- Ver arriba los archivos cambiados y abrir el diff del IDE al hacer doble clic.

### Gestionar PRs desde lista y detalle

Desde ambas vistas se debe poder:

- Hacer merge, cerrar, cambiar entre draft y ready, y cambiar la rama base.
- Pedir revisión y asignar personas.
- Consultar el estado de los checks y abrir detalles cuando fallen.

### Vista de detalle del PR

Al hacer doble clic en un PR de la lista, abrir una vista dedicada, siguiendo la referencia de IntelliJ:

- Mostrar el título con edición en el mismo lugar mediante un botón de edición; no abrir un diálogo aparte.
- Mostrar la relación `base <- rama de origen` en lugar de «View Timeline». Permitir cambiar la base y copiar el nombre de la rama.
- Mostrar los archivos cambiados. Solo los archivos con comentarios llevan un icono de conversación y su número; el doble clic abre el diff de ese archivo.
- Reutilizar primero el diff del IDE para mostrar las conversaciones inline; si no es viable, evaluar una alternativa que conserve los comentarios.
- Ofrecer merge, cierre, draft/ready, solicitud de review y asignación, además del estado y detalle de checks.

### Lista de pull requests

- Mantener la lista disponible al cerrar y volver a abrir la tool window, evitando mostrarla vacía mientras se vuelve a cargar.
- Incorporar búsqueda y filtros como en IntelliJ: estado, autor, label, assignee, review y orden.
- Retirar el visor independiente **Changes and Review Comments** actualmente agregado; la revisión debe ocurrir desde la vista del PR.

### Referencias visuales del usuario

Las capturas adjuntas a la conversación son las referencias para reproducir el flujo y su presentación. En particular:

- **New Pull Request y archivos cambiados:** `Image #1`, `Image #5`.
- **Selección de labels, reviewers y assignees:** `Image #2`, `Image #3`, `Image #4`.
- **Detalle, título editable, ramas, diff y acciones:** `Image #6`–`Image #12`.
- **Lista, búsqueda, filtros, orden y selección:** `Image #13`–`Image #20`.

> Las imágenes siguen adjuntas a esta conversación; este documento las referencia por número, no contiene copias locales de los archivos de imagen.
