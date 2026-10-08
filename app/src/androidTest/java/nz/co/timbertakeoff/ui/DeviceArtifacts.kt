package nz.co.timbertakeoff.ui

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Gradle copies this directory before uninstalling the instrumented application. */
internal fun deviceArtifact(context: Context, name: String): File {
    val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    val directory = configured?.let(::File) ?: checkNotNull(context.getExternalFilesDir(null))
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create test output directory $directory" }
    return File(directory, name)
}
