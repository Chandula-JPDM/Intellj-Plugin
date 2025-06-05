package com.github.chandulajpdm.intelljplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.StdFileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.*

class ClearLogPrefix : AnAction() {
    private var isModifed = false

    private var project: Project? = null

    private var firstTime = false

    override fun actionPerformed(event: AnActionEvent) {
        this
        actionEvent = event
        this.project = event.project
        this.isModifed = false
        val sourceRoots = event.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY) as Array<VirtualFile>?
        if (sourceRoots == null) {
            giveNoSelectionWarning()
            return
        }
        JavaFilesCount = 0
        for (sourceRoot in sourceRoots) {
            if (sourceRoot.isDirectory) {
                ExploreSourceFiles(sourceRoot)
            } else {
                identifyJavaFiles(sourceRoot, 1)
            }
        }
        if (!this.isModifed) giveClearWarning()
        val readRunner = Runnable {
            for (sourceRoot in sourceRoots) {
                val fileDocumentManager = FileDocumentManager.getInstance()
                fileDocumentManager.saveAllDocuments()
                sourceRoot.refresh(true, true)
            }
        }
        ApplicationManager.getApplication().invokeLater {
            CommandProcessor.getInstance()
                .executeCommand(
                    this@ClearLogPrefix.project,
                    { ApplicationManager.getApplication().runReadAction(readRunner) },
                    "DiskRead",
                    null
                )
        }
    }

    private fun identifyJavaFiles(file: VirtualFile, increase: Int) {
        val fileTypeManager = FileTypeManager.getInstance()
        if (!fileTypeManager.isFileIgnored(file) && fileTypeManager.getFileTypeByFile(file) === StdFileTypes.JAVA) {
            JavaFilesCount += increase
            try {
                clearLogEntries(file)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun ExploreSourceFiles(virtualFile: VirtualFile) {
        val children = virtualFile.children ?: return
        for (child in children) {
            identifyJavaFiles(child, 1)
            ExploreSourceFiles(child)
        }
    }

    @Throws(IOException::class)
    private fun clearLogEntries(vFile: VirtualFile) {
        var loggerName: String? = null
        this.firstTime = true
        if (FileDocumentManager.getInstance().isFileModified(vFile)) FileDocumentManager.getInstance()
            .saveAllDocuments()
        val file = File(vFile.path)
        val lines = FileUtils.readLines(file)
        for (i in lines.indices) {
            val currentLine = lines[i]
            val className = file.name.replaceFirst(".java".toRegex(), ".class")
            if (this.firstTime) if (currentLine!!.contains("Logger") && currentLine.contains(className)) {
                val st = StringTokenizer(currentLine)
                while (st.hasMoreTokens()) {
                    if (st.nextToken() == "Logger") {
                        loggerName = st.nextToken()
                        break
                    }
                }
                this.firstTime = false
            }
            if ((currentLine!!.contains("$loggerName.info") || currentLine.contains("$loggerName.debug") || currentLine.contains(
                    "$loggerName.error"
                ) || currentLine.contains("$loggerName.warn")) &&
                currentLine.contains("\"") && currentLine.contains(vFile.name)
            ) {
                val builder = StringBuilder(currentLine)
                builder.replace(currentLine.indexOf("["), currentLine.indexOf("]") + 2, "")
                lines[i] = builder.toString()
                this.isModifed = true
            }
        }
        FileUtils.writeLines(file, lines)
    }

    private fun giveClearWarning() {
        val statusBar = WindowManager.getInstance().getStatusBar(
            (PlatformDataKeys.PROJECT.getData(
                actionEvent!!.dataContext
            ) as Project)
        )
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder("Log Entries Are Not Inserted", MessageType.WARNING, null)
            .setFadeoutTime(7500L).setTitle("Clear Warning").createBalloon().show(
                RelativePoint.getCenterOf(
                    statusBar.component!!
                ), Balloon.Position.atRight
            )
    }

    private fun giveNoSelectionWarning() {
        val statusBar = WindowManager.getInstance().getStatusBar(
            (PlatformDataKeys.PROJECT.getData(
                actionEvent!!.dataContext
            ) as Project)
        )
        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder("No Files Selected", MessageType.ERROR, null)
            .setFadeoutTime(7500L).setTitle("No File").createBalloon().show(
                RelativePoint.getCenterOf(
                    statusBar.component!!
                ), Balloon.Position.atRight
            )
    }

    override fun update(event: AnActionEvent) {
        requireNotNull(event) {
            String.format(
                "Argument for @NotNull parameter '%s' of %s.%s must not be null",
                *arrayOf<Any>("event", "ClearLogPrefix", "update")
            )
        }
        val presentation = event.presentation
        val project = event.project
        if (project == null) {
            presentation.isEnabledAndVisible = false
            return
        }
        presentation.isEnabledAndVisible = true
    }

    companion object {
        private var JavaFilesCount = 0

        private var actionEvent: AnActionEvent? = null
    }
}
