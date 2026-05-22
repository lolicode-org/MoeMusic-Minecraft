package org.lolicode.moemusic.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import org.lolicode.moemusic.platform.client.ui.config.ConfigScreenAccess

/** Fabric Mod Menu entrypoint that opens the config UI or a friendly fallback screen. */
class MoeMusicModMenuIntegration : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { parent ->
        ConfigScreenAccess.buildOrFallback(parent)
    }
}
