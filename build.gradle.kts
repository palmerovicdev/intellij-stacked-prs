import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import kotlin.text.isNotBlank

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// El toolchain es el JBR 25 de la IDEA instalada (org.gradle.java.installations.paths
// en gradle.properties). Bytecode 21: corre igual sobre el JBR 25 de la 2026.2.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // Sin puentes hacia los metodos por defecto de las interfaces de la plataforma:
        // en el modo por defecto Kotlin sobrescribe en cada clase todos los metodos por
        // defecto (tambien los deprecados), y el Plugin Verifier los cuenta como usos.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// null -> camino de CI, que descarga platformVersion (la misma version que la
// instalada). No-null -> todo contra el IDE ya instalado.
val localIde: String? = providers.gradleProperty("localIdePath").orNull?.takeIf(String::isNotBlank)

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Obligatorio: la pila vive en un repositorio Git y el plugin la lee con git4idea.
        bundledPlugin("Git4Idea")
        // GitRepository y GitRepositoryManager exponen tipos de dvcs (Repository.State,
        // AbstractRepositoryManager, VcsRepositoryMappingListener), y esos modulos de la
        // plataforma no entran solos en el classpath.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")

        // El plugin GitHub NO se compila: solo se referencian sus grupos de acciones por
        // id en stacklane-github.xml. Ni una clase suya en el classpath -> imposible usar
        // por descuido su API interna.

        if (localIde != null) {
            local(localIde)
        } else {
            create(
                providers.gradleProperty("platformType"),
                providers.gradleProperty("platformVersion"),
            )
        }
    }
}

intellijPlatform {
    // Indexar las opciones de Settings arranca un IDE headless que, contra el IDE local,
    // choca con el sandbox de runIde. Solo en CI.
    buildSearchableOptions = localIde == null

    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Sin cota superior: el nucleo solo usa API publica. La integracion con los menus
            // de Pull Requests se degrada sola (las acciones se ocultan) si cambia.
            untilBuild = provider { null }
        }

        changeNotes = provider {
            """
            <h3>0.3.0 &mdash; a branch in two stacks</h3>
            <ul>
              <li>A branch that is a layer of one stack and the base of another (or the base of several
                stacks) is no longer shown as “not part of a stack”. The window says how many stacks it
                is in and lists them first, marked HEAD.</li>
              <li>Opening one of them checks out its highest layer that is not also the base of another
                stack, so gh stack can show it.</li>
              <li>New Stack from such a branch checks out the base of its stack first, as it already did
                from any other layer.</li>
            </ul>
            <h3>0.2.1</h3>
            <ul>
              <li>New Stack: the note about being on a layer of another stack and the command preview
                wrap to the dialog width instead of widening it. The command preview wraps the same
                way in Add Layer and Labels.</li>
            </ul>
            <h3>0.2.0 &mdash; stacks whose branches are gone</h3>
            <ul>
              <li>Local stacks whose branches were deleted (closing the PR, by hand) are marked
                <i>Branches deleted</i> instead of failing on checkout.</li>
              <li><b>Forget Stack</b> stops tracking such a stack locally without touching GitHub or
                your working tree; <b>Recreate on Another Base</b> starts the same layers again on
                any base, restoring their last commits.</li>
              <li>New Stack detects layer names still held by a stack without branches and cleans it
                up first; names taken by an active stack are rejected before running anything.</li>
              <li>New Stack shows the resulting stack as a graph, with the base grouped by local and
                remote-only branches.</li>
              <li>Remote-only stacks are fetched with <code>gh stack checkout</code>; local ones use the
                IDE checkout.</li>
              <li>Fixed: repositories whose remote uses an SSH alias
                (<code>git@github-personal:org/repo</code>) are now matched to their pull requests.</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
              <li>Stacks tool window: layers, pull requests, draft/ready state, labels, review and CI.</li>
              <li>Start a stack, add a layer, publish as drafts or ready for review, sync and rebase.</li>
              <li>Labels, ready/draft and <code>stack-final</code> from the Pull Requests menus.</li>
            </ul>
            """.trimIndent()
        }
    }

    // verifyPlugin corre contra la IDEA instalada: no descarga IDEs. Y es la garantia de
    // «nada de API interna»: el build FALLA si aparece un uso interno, deprecado,
    // experimental o de API que no se puede extender/sobrescribir.
    pluginVerification {
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.MISSING_DEPENDENCIES,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.EXPERIMENTAL_API_USAGES,
        )
        ides {
            if (localIde != null) {
                local(localIde)
            } else {
                create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    // `./gradlew runIde -PrunIdeProject=/ruta/al/repo` abre ese proyecto en el sandbox.
    runIde {
        providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
    }
}
