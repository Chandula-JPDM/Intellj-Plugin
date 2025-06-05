package com.github.chandulajpdm.intelljplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.StdFileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiFile
import com.intellij.ui.awt.RelativePoint
import org.apache.commons.io.FileUtils
import org.jetbrains.annotations.NotNull
import java.io.File
import java.io.IOException
import java.util.*


class UpdateLogPrefix: AnAction() {

    private var JavaFilesCount: Int = 0

    private var actionEvent: AnActionEvent? = null

    private var isModified = false

    private var project: Project? = null

    private var firstTime = false

    override fun actionPerformed(event: AnActionEvent) {
        this
        actionEvent = event
        this.project = event.project
        JavaFilesCount = 0
        this.isModified = false
        val sourceRoots = event.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY)
        if (sourceRoots == null) {
            giveNoSelectionWarning()
            return
        }
        for (sourceRoot in sourceRoots) {
            if (sourceRoot.isDirectory) {
                ExploreSourceFiles(sourceRoot)
            } else {
                identifyJavaFiles(sourceRoot, 1)
            }
        }
        if (!this.isModified) giveUpdateWarning()
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
                    this@UpdateLogPrefix.project,
                    { ApplicationManager.getApplication().runReadAction(readRunner) },
                    "DiskRead",
                    null
                )
        }
    }

    private fun updateLoggerInFile(project: Project, psiFile: PsiFile) {
        val document = FileDocumentManager.getInstance().getDocument(psiFile.virtualFile) ?: return
        val content = document.text
        val lines = content.split("\n").toMutableList()

        val updatedLines = lines.mapIndexed { index, line ->
            val lineNumber = index + 1
            if (line.contains(Regex("\\blogger\\.(info|error|warn)\\("))) {
                line.replace(
                        Regex("\\[.*?\\.java:\\d+\\]"),
                        "[${getClassName(psiFile)}.java:$lineNumber]"
                ).ifEmpty {
                    line.replace(
                            Regex("\\blogger\\.(info|error|warn)\\((.*?)\\)"),
                            "logger.$1([${getClassName(psiFile)}.java:$lineNumber] $2)"
                    )
                }
            } else {
                line
            }
        }

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(updatedLines.joinToString("\n"))
        }
    }

    private fun getClassName(psiFile: PsiFile): String {
        val fileName = psiFile.virtualFile.name
        return fileName.substringBefore(".java")
    }

    private fun identifyJavaFiles(file: VirtualFile, increase: Int) {
        val fileTypeManager = FileTypeManager.getInstance()
        if (!fileTypeManager.isFileIgnored(file) && fileTypeManager.getFileTypeByFile(file) === StdFileTypes.JAVA) {
            JavaFilesCount += increase
            try {
                updateLogEntries(file)
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
    private fun updateLogEntries(vFile: VirtualFile) {
        var loggerName: String? = null
        this.firstTime = true
        if (FileDocumentManager.getInstance().isFileModified(vFile)) FileDocumentManager.getInstance()
            .saveAllDocuments()
        val file: File = File(vFile.path)
        val linesUpdate: MutableList<String> = FileUtils.readLines(file)
        for (i in linesUpdate.indices) {
            var currentLine = linesUpdate[i]
            val className: String = file.getName().replaceFirst(".java", ".class")
            if (this.firstTime) if (currentLine.contains("Logger") && currentLine.contains(className)) {
                val st: StringTokenizer = StringTokenizer(currentLine)
                while (st.hasMoreTokens()) {
                    if (st.nextToken().equals("Logger")) {
                        loggerName = st.nextToken()
                        break
                    }
                }
                this.firstTime = false
            }
            if ((currentLine.contains("$loggerName.info") || currentLine.contains("$loggerName.debug") || currentLine.contains(
                    "$loggerName.error"
                ) || currentLine.contains("$loggerName.warn")) &&
                !currentLine.contains(vFile.name + ":" + (i + 1))
            ) if (currentLine.contains(vFile.name)) {
                currentLine = currentLine.replaceFirst(":\\d+".toRegex(), ":" + (i + 1))
                linesUpdate[i] = currentLine
                this.isModified = true
            } else if (currentLine.contains("\"")) {
                val temp = vFile.name + ":" + (i + 1)
                val insertText = "[$temp] "
                if (currentLine.contains(".java")) {
                    println(temp)
                    val builder = StringBuilder(currentLine)
                    builder.replace(currentLine.indexOf("["), currentLine.indexOf("]") + 2, "")
                    currentLine = builder.toString()
                    currentLine =
                        currentLine.substring(0, currentLine.indexOf("\"") + 1) + insertText + currentLine.substring(
                            currentLine.indexOf("\"") + 1
                        )
                    linesUpdate[i] = currentLine
                    this.isModified = true
                } else {
                    currentLine =
                        currentLine.substring(0, currentLine.indexOf("\"") + 1) + insertText + currentLine.substring(
                            currentLine.indexOf("\"") + 1
                        )
                    linesUpdate[i] = currentLine
                    this.isModified = true
                }
            }
        }
        FileUtils.writeLines(file, linesUpdate)
    }

    private fun giveNoSelectionWarning() {
        val statusBar: StatusBar = WindowManager.getInstance()
            .getStatusBar((actionEvent?.let { PlatformDataKeys.PROJECT.getData(it.getDataContext()) } as Project)!!)
        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder("No Files Selected", MessageType.ERROR, null)
            .setFadeoutTime(7500L).setTitle("No File").createBalloon()
            .show(statusBar.component?.let { RelativePoint.getCenterOf(it) }, Balloon.Position.atRight)
    }

    private fun giveUpdateWarning() {
        val statusBar: StatusBar = WindowManager.getInstance()
            .getStatusBar((actionEvent?.getDataContext()?.let { PlatformDataKeys.PROJECT.getData(it) } as Project)!!)
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder("Log Entries Are Up to Date", MessageType.WARNING, null).setFadeoutTime(7500L)
            .setTitle("Update Warning").createBalloon()
            .show(statusBar.component?.let { RelativePoint.getCenterOf(it) }, Balloon.Position.atRight)
    }

    override fun update(@NotNull event: AnActionEvent) {
        requireNotNull(event) {
            String.format(
                "Argument for @NotNull parameter '%s' of %s.%s must not be null",
                *arrayOf<Any>("event", "UpdateLogPrefix", "update")
            )
        }
        val presentation: Presentation = event.presentation
        val project = event.project
        if (project == null) {
            presentation.setEnabledAndVisible(false)
            return
        }
        presentation.setEnabledAndVisible(true)
    }
}