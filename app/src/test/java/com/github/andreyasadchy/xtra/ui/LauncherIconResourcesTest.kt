package com.github.andreyasadchy.xtra.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LauncherIconResourcesTest {

    private val projectRoot: File = File(".").absoluteFile.resolve("..").normalize()

    @Test
    fun `manifest uses dedicated round launcher icon`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test
    fun `adaptive launcher icons use p4 background and monochrome asset`() {
        val iconXml = projectFile("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        val roundIconXml = projectFile("app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml").readText()

        assertTrue(iconXml.contains("@drawable/ic_launcher_background_p4"))
        assertTrue(iconXml.contains("@drawable/ic_launcher_foreground"))
        assertTrue(iconXml.contains("@drawable/ic_launcher_monochrome"))

        assertEquals(iconXml.trim(), roundIconXml.trim())
    }

    @Test
    fun `round launcher pngs exist for legacy launchers`() {
        val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

        densities.forEach { density ->
            assertTrue(projectFile("app/src/main/res/mipmap-$density/ic_launcher_round.png").exists())
        }
    }

    private fun projectFile(relativePath: String): File {
        return projectRoot.resolve(relativePath)
    }
}
