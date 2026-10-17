package com.upstack

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.UpstackBundle"

/**
 * Acceso tipado a los textos del plugin.
 *
 * Se delega en una instancia en vez de heredar: el constructor `DynamicBundle(String)`
 * esta deprecado y el Plugin Verifier lo cuenta. El de `(Class, String)` busca el fichero
 * con el classloader del plugin.
 */
internal object UpstackBundle {

    private val instance = DynamicBundle(UpstackBundle::class.java, BUNDLE)

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        instance.getMessage(key, *params)
}
