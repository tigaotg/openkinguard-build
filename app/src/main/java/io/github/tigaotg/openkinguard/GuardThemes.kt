package io.github.tigaotg.openkinguard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** Data-only theme. An imported theme can never execute code or change app permissions. */
data class GuardTheme(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    val mode: String,
    val primary: String,
    val secondary: String,
    val background: String,
    val surface: String,
)

object GuardThemeFormat {
    const val MAX_BYTES = 16 * 1024
    const val MAX_CUSTOM_THEMES = 30
    private val identifier = Regex("[a-z0-9][a-z0-9._-]{2,63}")
    private val hex = Regex("#[0-9a-fA-F]{6}")
    private val fields = setOf("schemaVersion", "id", "name", "author", "license", "mode",
        "primary", "secondary", "background", "surface")

    fun decode(source: String): GuardTheme {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "主题文件超过 16 KB" }
        val root = JSONObject(source)
        require(root.optInt("schemaVersion", -1) == 1) { "目前只支持主题格式版本 1" }
        val keys = root.keys()
        while (keys.hasNext()) {
            require(keys.next() in fields) { "主题包含未知字段；目前仅允许外观颜色" }
        }
        val theme = GuardTheme(
            id = root.getString("id"), name = root.getString("name"),
            author = root.getString("author"), license = root.getString("license"),
            mode = root.getString("mode"), primary = root.getString("primary"),
            secondary = root.getString("secondary"),
            background = root.getString("background"), surface = root.getString("surface"),
        )
        validate(theme)
        return theme
    }

    fun validate(theme: GuardTheme) {
        require(identifier.matches(theme.id)) { "主题 ID 只能使用小写英文、数字、点、横线或下划线" }
        require(theme.name.isNotBlank() && theme.name.length <= 32) { "主题名称需在 1～32 个字以内" }
        require(theme.author.isNotBlank() && theme.author.length <= 80) { "须注明作者（最多80字）" }
        require(theme.license.isNotBlank() && theme.license.length <= 80) { "须注明许可协议（最多80字）" }
        require(theme.mode == "light" || theme.mode == "dark") { "模式只能为 light 或 dark" }
        listOf(theme.primary, theme.secondary, theme.background, theme.surface).forEach {
            require(hex.matches(it)) { "颜色须为 #RRGGBB 格式" }
        }
    }

    fun encode(theme: GuardTheme): String {
        validate(theme)
        return JSONObject().put("schemaVersion", 1).put("id", theme.id)
            .put("name", theme.name).put("author", theme.author)
            .put("license", theme.license).put("mode", theme.mode)
            .put("primary", theme.primary).put("secondary", theme.secondary)
            .put("background", theme.background).put("surface", theme.surface)
            .toString(2) + "\n"
    }

    fun readLimited(stream: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        stream.use { source ->
            while (true) {
                val count = source.read(chunk)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "主题文件超过 16 KB" }
                output.write(chunk, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

class GuardThemeRepository(context: Context) {
    private val appContext = context.applicationContext
    companion object {
        val included = listOf(
            GuardTheme("builtin.lavender", "经典紫", "OpenKinGuard 项目", "GPL-3.0-or-later",
                "light", "#6851A5", "#746197", "#FAF8FF", "#FFFFFF"),
            GuardTheme("builtin.night", "深夜守护", "OpenKinGuard 项目", "GPL-3.0-or-later",
                "dark", "#C6B2FF", "#B8C8FF", "#12121A", "#20202B"),
            GuardTheme("builtin.forest", "森林绿", "OpenKinGuard 项目", "GPL-3.0-or-later",
                "light", "#18664A", "#426A55", "#F3FAF4", "#FFFFFF"),
            GuardTheme("builtin.sunrise", "暖阳橙", "OpenKinGuard 项目", "GPL-3.0-or-later",
                "light", "#9A480E", "#8D6235", "#FFF9F1", "#FFFFFF"),
            GuardTheme("builtin.contrast", "高对比黑白", "OpenKinGuard 项目", "GPL-3.0-or-later",
                "light", "#101010", "#343434", "#FFFFFF", "#FFFFFF"),
        )
    }

    private val prefs = context.applicationContext.getSharedPreferences("guard_theme_v1", Context.MODE_PRIVATE)
    private val imported = mutableStateListOf<GuardTheme>()
    var selectedId by mutableStateOf(prefs.getString("selected_theme", "builtin.lavender")
        ?: "builtin.lavender")
        private set

    init {
        runCatching {
            val saved = JSONArray(prefs.getString("installed_themes", "[]"))
            val seen = mutableSetOf<String>()
            for (index in 0 until minOf(saved.length(), GuardThemeFormat.MAX_CUSTOM_THEMES)) {
                val candidate = GuardThemeFormat.decode(saved.getJSONObject(index).toString())
                if (candidate.id !in included.map { it.id } && seen.add(candidate.id)) {
                    imported.add(candidate)
                }
            }
        }
        // 0.9.7 deliberately retires the dynamic/expressive palette. Devices
        // upgraded from 0.9.6 return to the lightweight classic palette, but
        // users who selected another built-in or imported palette keep it.
        if (themes.none { it.id == selectedId }) select(included.first().id)
    }

    val themes: List<GuardTheme> get() = included + imported
    val selected: GuardTheme get() = themes.firstOrNull { it.id == selectedId } ?: included.first()

    fun select(id: String) {
        require(themes.any { it.id == id }) { "找不到这个主题" }
        selectedId = id
        prefs.edit().putString("selected_theme", id).apply()
        GuardWidgetPublisher.redraw(appContext)
    }

    fun install(source: String): GuardTheme {
        val theme = GuardThemeFormat.decode(source)
        require(theme.id !in included.map { it.id }) { "不能覆盖应用自带主题" }
        val existing = imported.indexOfFirst { it.id == theme.id }
        if (existing < 0) {
            require(imported.size < GuardThemeFormat.MAX_CUSTOM_THEMES) { "最多允许导入30个自定义主题" }
            imported.add(theme)
        } else {
            imported[existing] = theme
        }
        persistImported()
        return theme
    }

    fun remove(id: String) {
        require(id !in included.map { it.id }) { "不能删除内置主题" }
        imported.removeAll { it.id == id }
        if (selectedId == id) select(included.first().id)
        persistImported()
    }

    private fun persistImported() {
        val items = JSONArray()
        imported.forEach { items.put(JSONObject(GuardThemeFormat.encode(it))) }
        prefs.edit().putString("installed_themes", items.toString()).apply()
    }

    fun colorScheme(): ColorScheme {
        val theme = selected
        fun parse(value: String) = Color(android.graphics.Color.parseColor(value))
        fun readable(value: Color) = if (value.red * .299f + value.green * .587f +
            value.blue * .114f > .58f) Color.Black else Color.White
        val primary = parse(theme.primary)
        val secondary = parse(theme.secondary)
        val background = parse(theme.background)
        val surface = parse(theme.surface)
        fun mix(a: Color, b: Color, toward: Float) = Color(
            red = a.red * (1f - toward) + b.red * toward,
            green = a.green * (1f - toward) + b.green * toward,
            blue = a.blue * (1f - toward) + b.blue * toward,
        )
        // Material3 NavigationBar uses surfaceContainer and secondaryContainer,
        // not surface. Explicitly derive them for every imported palette.
        val container = mix(surface, primary, if (theme.mode == "dark") .18f else .14f)
        val indicator = mix(surface, primary, if (theme.mode == "dark") .30f else .23f)
        return if (theme.mode == "dark") darkColorScheme(
            primary = primary, onPrimary = readable(primary), secondary = secondary,
            onSecondary = readable(secondary), background = background,
            onBackground = readable(background), surface = surface, onSurface = readable(surface),
            surfaceVariant = surface, onSurfaceVariant = readable(surface),
            surfaceContainer = container, surfaceContainerLow = mix(surface, primary, .16f),
            surfaceContainerHigh = mix(surface, primary, .25f),
            primaryContainer = indicator, onPrimaryContainer = readable(indicator),
            secondaryContainer = indicator, onSecondaryContainer = readable(indicator),
        ) else lightColorScheme(
            primary = primary, onPrimary = readable(primary), secondary = secondary,
            onSecondary = readable(secondary), background = background,
            onBackground = readable(background), surface = surface, onSurface = readable(surface),
            surfaceVariant = surface, onSurfaceVariant = readable(surface),
            surfaceContainer = container, surfaceContainerLow = mix(surface, primary, .14f),
            surfaceContainerHigh = mix(surface, primary, .20f),
            primaryContainer = indicator, onPrimaryContainer = readable(indicator),
            secondaryContainer = indicator, onSecondaryContainer = readable(indicator),
        )
    }
}
