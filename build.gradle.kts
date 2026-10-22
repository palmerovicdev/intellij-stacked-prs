import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
    // El framework de pruebas de la plataforma lo pide en tiempo de ejecucion.
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        // Los tests de flujo (StackService con un `gh` falso) corren dentro de una aplicacion
        // headless: son unas librerias, no otro IDE.
        testFramework(TestFrameworkType.Platform)
        // Obligatorio: la pila vive en un repositorio Git y el plugin la lee con git4idea.
        bundledPlugin("Git4Idea")
        // GitRepository y GitRepositoryManager exponen tipos de dvcs (Repository.State,
        // AbstractRepositoryManager, VcsRepositoryMappingListener), y esos modulos de la
        // plataforma no entran solos en el classpath.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")

        // El plugin GitHub NO se compila: solo se referencian sus grupos de acciones por
        // id en upstack-github.xml. Ni una clase suya en el classpath -> imposible usar
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
            <h3>0.12.3 &mdash; Add Layer suggests existing branches</h3>
            <ul>
              <li>The new layer's name autocompletes with the local branches gh-stack can adopt: every one
                except the trunk and the layers of this stack, and the layers of any other stack it tracks.
                An existing branch joins the stack as it is, without a rebase, and the dialog says so.</li>
            </ul>
            <h3>0.12.2 &mdash; a new name: Upstack</h3>
            <ul>
              <li>The plugin is now <b>Upstack</b> (<code>com.upstack</code>): its tool window, actions and
                settings page (Settings | Version Control | Upstack). Nothing changes in how it works.</li>
            </ul>
            <h3>0.12.1 &mdash; ready for the Marketplace</h3>
            <ul>
              <li>A complete description and release notes for this page. Nothing changes in how the plugin
                works.</li>
            </ul>
            <h3>0.12.0 &mdash; check the environment and log in</h3>
            <ul>
              <li><b>Check</b> in Settings | Version Control | Upstack: which <code>gh</code> is used and
                where, the account on each host, the <code>gh-stack</code> version, the repository's remotes and
                whether GitHub supports stacked pull requests there. It only reads, and every command goes to
                the Log.</li>
              <li>Whatever is missing has its fix next to it: <i>Install GitHub CLI</i>, <i>Install
                gh-stack</i>, <i>Upgrade gh-stack</i> and <i>Log In&hellip;</i>.</li>
              <li><b>Log In without a terminal</b>: <code>gh auth login --web</code> asks GitHub for a
                one-time code, and a dialog shows it, copies it and opens the page where it goes. The token
                stays with gh.</li>
              <li>Without a session, the Stacks window says so and offers to log in, instead of showing gh's
                error as is.</li>
              <li>A warning, once per version, if <code>gh-stack</code> is not v0.1.1, the version whose JSON
                and exit codes the plugin reads. It blocks nothing.</li>
            </ul>
            <h3>0.11.1 &mdash; the title of a new pull request</h3>
            <ul>
              <li>The proposed title of a new pull request is its branch name, in <i>Publish Stack&hellip;</i>
                and in a layer's <i>Publish</i>. The description still comes from its commits.</li>
            </ul>
            <h3>0.11.0 &mdash; layers without a branch, delete, rename and publish a layer</h3>
            <ul>
              <li>A layer whose branch is gone is marked <i>Branch deleted</i> (or <i>Only on origin</i>), and
                a banner explains that gh stack cannot rebase, sync or push without it.</li>
              <li><b>Restore Branch</b> recreates it from the remote or at its last known commit;
                <b>Remove From Stack&hellip;</b> takes it out of the stack.</li>
              <li><b>Delete Layer&hellip;</b> for the top layer without a pull request, with its local and,
                optionally, remote branch.</li>
              <li><b>Rename Branch&hellip;</b> for a layer without a pull request, here and on GitHub, and
                <b>Edit Title&hellip;</b> for a pull request.</li>
              <li><b>Publish as Draft&hellip;</b> and <b>Publish Ready for Review&hellip;</b> from a layer's
                menu, with an editable title and description.</li>
            </ul>
            <h3>0.10.0 &mdash; guided conflicts</h3>
            <ul>
              <li>When <code>gh stack rebase</code> stops, the IDE's conflicts dialog opens on its own.</li>
              <li>The banner says which layer stopped, its position in the stack and the commit git could not
                apply, and counts the files still in conflict.</li>
              <li>When none is left, the banner turns blue and offers <b>Continue</b>
                (<code>gh stack rebase --continue</code>). <i>Abort</i> asks first.</li>
              <li>The stack stays on screen during the rebase.</li>
            </ul>
            <h3>0.9.0 &mdash; merge the stack</h3>
            <ul>
              <li><b>Merge Stack&hellip;</b> and, from a layer, <b>Merge Up to Here&hellip;</b>: GitHub merges
                that pull request and every one below it at once, or none (<code>gh stack merge</code>).</li>
              <li>The dialog shows which layers go in, their review and CI, and only the merge methods the
                repository allows, remembering the last one. With a merge queue, they join the queue.</li>
              <li>It checks first that the number is not also a stack number, so no other stack is merged by
                mistake.</li>
              <li>Afterwards, <b>Sync and Prune</b> (<code>gh stack sync --prune</code>) deletes the merged
                branches.</li>
            </ul>
            <h3>0.8.0 &mdash; close a whole stack</h3>
            <ul>
              <li><b>Close Stack&hellip;</b>: unstack it, close its open pull requests with an optional
                comment, and delete its remote and local branches, one checkbox per step.</li>
              <li>In a safe order: unstack first, while the branches still exist. Local branches that differ
                from the remote are flagged and kept by default, and a branch that is the base of another stack
                is never deleted.</li>
            </ul>
            <h3>0.7.0 &mdash; each layer at a glance</h3>
            <ul>
              <li><b>Show Layer Changes</b> in a layer's menu: the IDE diff with what the layer adds on top
                of the one below, the same changes its pull request shows, even if the layer below moved
                on.</li>
              <li><b>Wrong base</b>: a layer whose pull request targets another branch than the layer below
                is marked, and <i>Change Pull Request Base</i> points it back
                (<code>gh pr edit &lt;url&gt; --base &lt;branch&gt;</code>).</li>
              <li>Each layer shows its size (<code>+120 &minus;30</code>), and <i>Conflicts</i> or
                <i>Behind</i> when GitHub cannot merge it as is.</li>
              <li>Narrower tooltips, with each command in its own code block and links in blue.</li>
              <li>Layers without a pull request say so once; the graph line is visible in dark themes;
                settings moved to Settings | Version Control | Upstack.</li>
            </ul>
            <h3>0.6.0 &mdash; what each option does, and what comes next</h3>
            <ul>
              <li>Every toolbar button, menu item, banner button and dialog option explains on hover what it
                does and the exact command it runs, with the real branch or pull request.</li>
              <li>A layer's right-click menu holds only that layer's actions. Whole-stack actions (Add Layer,
                Publish Stack) are in the toolbar and in the right-click outside the rows.</li>
              <li>After a rebase, a banner offers <b>Push Stack</b> (<code>gh stack push</code>) until the
                branches are pushed, from the IDE or the terminal.</li>
            </ul>
            <h3>0.5.0 &mdash; carry a change up without the trunk</h3>
            <ul>
              <li><b>Rebase Upstack</b> (<code>gh stack rebase --upstack --no-trunk</code>) carries a lower
                layer's commits to the layers above without fetching or rebasing onto the trunk, so the
                only conflicts are the ones the change causes.</li>
              <li>After a commit in the IDE on a layer that is not the top, a notification offers it, or it
                runs every time (Settings | Tools | Upstack).</li>
              <li>The <i>needs rebase</i> banner tells an outdated layer (rebase upstack) from a bottom
                layer behind the trunk (rebase the whole stack), and explains the difference once.</li>
              <li>Rebase in the toolbar is a menu: upstack, whole stack, downstack and layers only. Each
                layer has <i>Rebase Upstack from Here</i>.</li>
              <li>After a rebase, <b>Push Stack</b> (<code>gh stack push</code>).</li>
              <li>The first rebase in a repository asks whether to turn on <code>git rerere</code>, as gh
                stack does in the terminal, and stores the answer in the same keys.</li>
            </ul>
            <h3>0.4.0 &mdash; publish deciding pull request by pull request</h3>
            <ul>
              <li><b>Publish Stack…</b>: for each layer, ready for review or draft, and the title and
                description of new pull requests, proposed from their commits.</li>
              <li>Actions on the whole stack say <i>Stack</i>; every layer action has an icon; right-click
                opens the menu of the row under the mouse.</li>
            </ul>
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

    // Solo los usa release.yml, y solo si estan los secretos del repositorio (ver
    // docs/development.md, «Publicar»). CERTIFICATE_CHAIN, PRIVATE_KEY y PRIVATE_KEY_PASSWORD
    // los da el generador de JetBrains; PUBLISH_TOKEN sale del perfil del Marketplace.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Una version con sufijo (0.13.0-beta.1) sale al canal con ese nombre en vez de al
        // estable, que es como el Marketplace distingue las preliminares.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
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
