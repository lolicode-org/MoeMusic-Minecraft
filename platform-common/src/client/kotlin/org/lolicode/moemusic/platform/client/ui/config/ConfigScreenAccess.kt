package org.lolicode.moemusic.platform.client.ui.config

import net.minecraft.client.gui.screens.Screen

/**
 * Loader-neutral access point for the optional MoeMusic config screen.
 *
 * Fabric registers the real Cloth Config-backed screen only when the dependency
 * is present. Callers can always ask for a screen and will receive a friendly
 * fallback when the optional dependency is missing.
 */
object ConfigScreenAccess {

    @Volatile
    private var configScreenFactory: ((Screen?) -> Screen)? = null

    fun register(factory: (Screen?) -> Screen) {
        configScreenFactory = factory
    }

    fun clear() {
        configScreenFactory = null
    }

    fun isAvailable(): Boolean = configScreenFactory != null

    fun buildOrFallback(parent: Screen?): Screen =
        configScreenFactory?.invoke(parent) ?: MissingClothConfigScreen(parent)
}
