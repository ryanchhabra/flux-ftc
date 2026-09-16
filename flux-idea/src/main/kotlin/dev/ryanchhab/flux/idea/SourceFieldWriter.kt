package dev.ryanchhab.flux.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Rewrites one `@FluxLive` field's literal in its source file -- the "write back to source" half
 * of live-tuning.md §6: when a slider or text box commits a new value, the source file is what
 * makes it stick (there is otherwise nothing to transcribe, per the design doc's whole point).
 *
 * Two halves, split so the field-locating logic is unit-testable without the IntelliJ platform:
 *  - [computeUpdatedText] is pure: given the *current* text of a file, it re-locates the field
 *    with [FluxLiveSourceScanner.scanText] -- never against a cached offset from an earlier
 *    Refresh -- and returns the new text, or null if the field can no longer be found (the file
 *    changed underneath in a way that invalidated it; the caller reports this rather than
 *    guessing at an offset that might now point at the wrong token).
 *  - [write] is the IntelliJ-platform half: reads the *live* Document (so an already-open, edited
 *    editor is respected rather than clobbered by stale on-disk content), applies
 *    [computeUpdatedText] to it inside a write action, and saves. Must run on a background thread
 *    (it hops to the EDT itself via `invokeAndWait`); never call it from the EDT with a lock
 *    already held.
 */
object SourceFieldWriter {

    /**
     * Pure. Re-locates `simpleClassName.fieldName`'s literal inside [currentText] (scanned as
     * [fileName] would be, i.e. only the extension matters) and replaces just that literal span
     * with [newLiteral]. Returns null -- never a best-effort guess -- if the field isn't found,
     * so a stale UI row can never mangle an unrelated part of the file.
     */
    fun computeUpdatedText(
        fileName: String,
        currentText: String,
        simpleClassName: String,
        fieldName: String,
        newLiteral: String,
    ): String? {
        val fields = FluxLiveSourceScanner.scanText(File(fileName), currentText)
        val field = fields.firstOrNull { it.simpleClassName == simpleClassName && it.fieldName == fieldName }
            ?: return null
        val r = field.literalRange
        return currentText.substring(0, r.first) + newLiteral + currentText.substring(r.last + 1)
    }

    /**
     * Writes [newLiteral] into [file] for `simpleClassName.fieldName`, via the Document so any
     * open editor is updated too. Safe to call from a background thread (this hops to the EDT
     * itself for the write action). Returns true on success; false if the field could no longer
     * be located (file changed since Refresh -- caller should tell the user to hit Refresh again)
     * or the file isn't open-able.
     */
    fun write(
        project: Project,
        file: File,
        simpleClassName: String,
        fieldName: String,
        newLiteral: String,
    ): Boolean {
        var result = false
        ApplicationManager.getApplication().invokeAndWait {
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@invokeAndWait
            val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return@invokeAndWait
            val currentText = document.text
            val updated = computeUpdatedText(file.name, currentText, simpleClassName, fieldName, newLiteral)
                ?: return@invokeAndWait

            WriteCommandAction.runWriteCommandAction(project, "Flux: Live-Tune $simpleClassName.$fieldName", null, {
                document.setText(updated)
                FileDocumentManager.getInstance().saveDocument(document)
            })
            result = true
        }
        return result
    }
}
