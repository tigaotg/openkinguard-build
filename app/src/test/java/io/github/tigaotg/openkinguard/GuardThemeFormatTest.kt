package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class GuardThemeFormatTest {
    private val theme = GuardTheme(
        id = "community.moss", name = "青苔", author = "示例贡献者",
        license = "CC0-1.0", mode = "dark", primary = "#88BB99",
        secondary = "#DDEECC", background = "#14221A", surface = "#20382A",
    )

    @Test fun exportImportPreservesAuthorAndLicense() {
        assertEquals(theme, GuardThemeFormat.decode(GuardThemeFormat.encode(theme)))
    }

    @Test fun unknownKeysAndExecutablePayloadAreRejected() {
        val source = GuardThemeFormat.encode(theme).trim().dropLast(1) +
            ",\"script\":\"alert(1)\"}"
        assertRejected { GuardThemeFormat.decode(source) }
    }

    @Test fun missingAttributionIsRejected() {
        val withoutAuthor = JSONObject(GuardThemeFormat.encode(theme)).put("author", "")
        assertRejected { GuardThemeFormat.decode(withoutAuthor.toString()) }
        assertRejected { GuardThemeFormat.validate(theme.copy(license = "")) }
    }

    @Test fun invalidColorAndModeAreRejected() {
        assertRejected { GuardThemeFormat.validate(theme.copy(primary = "url(https://example.com)")) }
        assertRejected { GuardThemeFormat.validate(theme.copy(mode = "execute")) }
        assertRejected { GuardThemeFormat.validate(theme.copy(id = "../../etc")) }
    }

    @Test fun fileSizeLimitIsEnforced() {
        assertRejected { GuardThemeFormat.decode("X".repeat(GuardThemeFormat.MAX_BYTES + 1)) }
    }

    private fun assertRejected(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Expected invalid theme to be rejected", rejected)
    }
}
