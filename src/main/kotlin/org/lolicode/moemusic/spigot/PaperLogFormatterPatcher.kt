package org.lolicode.moemusic.spigot

import java.lang.reflect.Array
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Paper / Purpur / Folia console appenders use [net.minecrell.terminalconsole.util.LoggerNamePatternSelector],
 * which by default prepends `[%logger]` to every log event from non-Minecraft loggers.
 *
 * For classes in the shared core (`org.lolicode.moemusic.*`), this causes full class names to be
 * displayed in brackets (e.g. `[org.lolicode.moemusic.core.playback.ServerPlaybackController]`).
 *
 * This patcher registers `org.lolicode.moemusic.` with Paper's pattern selector at runtime so that
 * all internal core logs are cleanly formatted with `[pluginTag]` (e.g. `[MoeMusic]`), matching standard
 * Bukkit plugin conventions.
 */
internal object PaperLogFormatterPatcher {

    private const val TARGET_PACKAGE_PREFIX = "org.lolicode.moemusic."
    private const val LOGGER_NAME_PATTERN_SELECTOR = "net.minecrell.terminalconsole.util.LoggerNamePatternSelector"
    private const val LOGGER_NAME_SELECTOR = "net.minecrell.terminalconsole.util.LoggerNamePatternSelector\$LoggerNameSelector"
    private const val PATTERN_LAYOUT = "org.apache.logging.log4j.core.layout.PatternLayout"

    fun patch(pluginTag: String, logger: Logger) {
        runCatching {
            val logManagerClass = runCatching { Class.forName("org.apache.logging.log4j.LogManager") }.getOrNull() ?: return
            val getContextMethod = logManagerClass.getMethod("getContext", Boolean::class.javaPrimitiveType)
            val loggerContext = getContextMethod.invoke(null, false) ?: return

            val getConfigurationMethod = loggerContext.javaClass.getMethod("getConfiguration")
            val configuration = getConfigurationMethod.invoke(loggerContext) ?: return

            val getAppendersMethod = configuration.javaClass.getMethod("getAppenders")
            @Suppress("UNCHECKED_CAST")
            val appenders = getAppendersMethod.invoke(configuration) as? Map<String, Any> ?: return

            var patchedCount = 0
            for ((appenderName, appender) in appenders) {
                if (patchAppender(appenderName, appender, configuration, pluginTag)) {
                    patchedCount++
                }
            }
            if (patchedCount > 0) {
                logger.log(Level.FINE, "Patched $patchedCount Paper/Purpur console appender(s) for [$pluginTag]")
            }
        }.onFailure {
            logger.log(Level.FINE, "Paper log formatter patch not applied", it)
        }
    }

    private fun patchAppender(
        appenderName: String,
        appender: Any,
        configuration: Any,
        pluginTag: String,
    ): Boolean {
        val layout = getLayout(appender) ?: return false
        if (layout.javaClass.name != PATTERN_LAYOUT) return false

        val patternSelector = getPatternSelector(layout) ?: return false
        if (patternSelector.javaClass.name != LOGGER_NAME_PATTERN_SELECTOR) return false

        return patchLoggerNamePatternSelector(patternSelector, configuration, appenderName, pluginTag)
    }

    private fun getLayout(appender: Any): Any? {
        val getLayoutMethod = appender.javaClass.methods.firstOrNull {
            it.name == "getLayout" && it.parameterCount == 0
        } ?: return null
        return getLayoutMethod.invoke(appender)
    }

    private fun getPatternSelector(layout: Any): Any? {
        val selectorField = layout.javaClass.declaredFields.firstOrNull {
            it.name == "patternSelector"
        }?.apply { isAccessible = true }
        var selector = selectorField?.get(layout)
        if (selector != null) return selector

        val eventSerializerField = layout.javaClass.declaredFields.firstOrNull {
            it.name == "eventSerializer"
        }?.apply { isAccessible = true }
        val eventSerializer = eventSerializerField?.get(layout) ?: return null

        val selectorInSerializerField = eventSerializer.javaClass.declaredFields.firstOrNull {
            it.type.name == LOGGER_NAME_PATTERN_SELECTOR || it.name == "patternSelector"
        }?.apply { isAccessible = true }
        return selectorInSerializerField?.get(eventSerializer)
    }

    private fun patchLoggerNamePatternSelector(
        patternSelector: Any,
        configuration: Any,
        appenderName: String,
        pluginTag: String,
    ): Boolean {
        val formattersField = patternSelector.javaClass.declaredFields.firstOrNull {
            it.name == "formatters"
        }?.apply { isAccessible = true } ?: return false

        @Suppress("UNCHECKED_CAST")
        val formattersList = formattersField.get(patternSelector) as? MutableList<Any> ?: return false

        val selectorClass = Class.forName(LOGGER_NAME_SELECTOR)
        val nameField = selectorClass.declaredFields.firstOrNull {
            it.name == "name"
        }?.apply { isAccessible = true }

        if (formattersList.any { nameField?.get(it) == TARGET_PACKAGE_PREFIX }) {
            return false
        }

        val pattern = if (appenderName.contains("File", ignoreCase = true)) {
            "[%d{HH:mm:ss}] [%t/%level]: [$pluginTag] %stripAnsi{%msg}%n%xEx{full}"
        } else {
            "%highlightError{[%d{HH:mm:ss} %level]: [$pluginTag] %msg%n%xEx{full}}"
        }

        val createPatternParserMethod = Class.forName(PATTERN_LAYOUT).methods.firstOrNull {
            it.name == "createPatternParser" && it.parameterCount == 1
        } ?: return false
        val parser = createPatternParserMethod.invoke(null, configuration) ?: return false

        val parseMethod = parser.javaClass.methods.firstOrNull {
            it.name == "parse" && it.parameterCount == 4
        } ?: parser.javaClass.methods.firstOrNull {
            it.name == "parse" && it.parameterCount == 1
        } ?: return false

        val formattersListResult = (if (parseMethod.parameterCount == 4) {
            parseMethod.invoke(parser, pattern, false, true, false)
        } else {
            parseMethod.invoke(parser, pattern)
        }) as? List<*> ?: return false

        val ctor = selectorClass.declaredConstructors.firstOrNull {
            it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
        }?.apply { isAccessible = true } ?: return false

        val formattersArray = Array.newInstance(ctor.parameterTypes[1].componentType, formattersListResult.size)
        for (i in formattersListResult.indices) {
            Array.set(formattersArray, i, formattersListResult[i])
        }

        val newSelector = ctor.newInstance(TARGET_PACKAGE_PREFIX, formattersArray)
        formattersList.add(0, newSelector)
        return true
    }
}
