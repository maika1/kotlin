/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.NonClasspathDirectoriesScope
import com.intellij.util.containers.SLRUMap
import org.jetbrains.kotlin.codegen.inline.getOrPut
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.core.script.configuration.ScriptingSupport
import org.jetbrains.kotlin.idea.core.script.configuration.listener.ScriptConfigurationUpdater
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsIndexer
import org.jetbrains.kotlin.idea.scripting.gradle.importing.GradleKtsContext
import org.jetbrains.kotlin.idea.scripting.gradle.importing.KotlinDslScriptModel
import org.jetbrains.kotlin.idea.scripting.gradle.importing.toScriptConfiguration
import org.jetbrains.kotlin.idea.util.getProjectJdkTableSafe
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.nio.file.FileSystems

private class Configuration(
    val context: GradleKtsContext,
    models: List<KotlinDslScriptModel>
) {
    companion object {
        const val MAX_SCRIPTS_CACHED = 50
    }

    class Fat(
        val scriptConfiguration: ScriptCompilationConfigurationWrapper,
        val classFilesScope: GlobalSearchScope
    )

    private val memoryCache = SLRUMap<VirtualFile, Fat>(MAX_SCRIPTS_CACHED, MAX_SCRIPTS_CACHED)

    private fun String.toVirtualFile(): VirtualFile {
        StandardFileSystems.local()?.findFileByPath(this)?.let {
            return it
        }

        StandardFileSystems.jar()?.findFileByPath(this)?.let {
            return it
        }

        // TODO: report this somewhere, but do not throw: assert(res != null, { "Invalid classpath entry '$this': exists: ${exists()}, is directory: $isDirectory, is file: $isFile" })

        return VfsUtil.findFile(FileSystems.getDefault().getPath(this), true)!!
    }

    fun Collection<String>.toVirtualFiles() =
        map { it.toVirtualFile() }

    val sdk = findSdk()

    private fun findSdk(): Sdk? {
        val javaHome = try {
            context.javaHome?.let { VfsUtil.findFileByIoFile(it, true) }
        } catch (e: Throwable) {
            null
        }
        return getProjectJdkTableSafe().allJdks.find { it.homeDirectory == javaHome }
    }

    val scripts = models.associateBy { it.file }

    val classFilePath = models.flatMapTo(mutableSetOf()) { it.classPath }
    val classFiles = classFilePath.toVirtualFiles()
    val classFilesScope = NonClasspathDirectoriesScope.compose(classFiles)

    val sourcePath = models.flatMapTo(mutableSetOf()) { it.sourcePath }
    val sources = sourcePath.toVirtualFiles()
    val sourcesScope = NonClasspathDirectoriesScope.compose(sources)

    fun scriptModel(file: VirtualFile): KotlinDslScriptModel? {
        return scripts[FileUtil.toSystemDependentName(file.path)]
    }

    private fun Sdk.isAlreadyIndexed(): Boolean {
        return ModuleManager.getInstance(context.project).modules.any { ModuleRootManager.getInstance(it).sdk == this }
    }

    operator fun get(key: VirtualFile): Fat? {
        return memoryCache.getOrPut(key) {
            val model = scriptModel(key) ?: return null
            val configuration = model.toScriptConfiguration(context) ?: return null

            val scriptSdk = sdk ?: ScriptConfigurationManager.getScriptDefaultSdk(context.project)
            if (scriptSdk != null && !scriptSdk.isAlreadyIndexed()) {
                return Fat(
                    configuration,
                    NonClasspathDirectoriesScope.compose(
                        scriptSdk.rootProvider.getFiles(OrderRootType.CLASSES).toList() + model.classPath.toVirtualFiles()
                    )
                )

            } else {
                Fat(
                    configuration,
                    NonClasspathDirectoriesScope.compose(model.classPath.toVirtualFiles())
                )
            }
        }
    }
}

class GradleScriptingSupport(val project: Project) : ScriptingSupport {
    @Volatile
    private var configuration: Configuration? = null

    private val rootsIndexer = ScriptClassRootsIndexer(project)

    private fun Sdk.isAlreadyIndexed(): Boolean {
        return ModuleManager.getInstance(project).modules.any { ModuleRootManager.getInstance(it).sdk == this }
    }

    fun replace(context: GradleKtsContext, models: List<KotlinDslScriptModel>) {
        KotlinDslScriptModels.write(project, models)

        val old = configuration
        val newConfiguration = Configuration(context, models)
        configuration = newConfiguration

        rootsIndexer.transaction {
            if (shouldReindex(old, newConfiguration)) {
                rootsIndexer.markNewRoot()
            }
        }


        ApplicationManager.getApplication().invokeLater {
            val openFiles = FileEditorManager.getInstance(project).openFiles
            val openScripts = openFiles.filter { configuration?.scriptModel(it) != null }

            openScripts.forEach {
                PsiManager.getInstance(project).findFile(it)?.let { psiFile ->
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                }
            }
        }

        // todo: update unindexed roots
        // todo: remove notification, etc..
    }

    private fun shouldReindex(
        old: Configuration?,
        new: Configuration
    ): Boolean {
        if (old == null) return true
        if (new.sdk?.isAlreadyIndexed() == false) return true

        if (!new.classFilePath.any { it !in old.classFilePath }) return true
        if (!new.sourcePath.any { it !in old.sourcePath }) return true

        return false
    }

    fun load() {
        val gradleProjectSettings = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
            .getLinkedProjectsSettings()
            .filterIsInstance<GradleProjectSettings>().firstOrNull() ?: return

        val javaHome = File(gradleProjectSettings.gradleJvm ?: return)

        val models = KotlinDslScriptModels.read(project) ?: return
        configuration = Configuration(GradleKtsContext(project, javaHome), models)
    }

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            load()

            rootsIndexer.transaction {
                rootsIndexer.markNewRoot()
            }


            ApplicationManager.getApplication().invokeLater {
                val openFiles = FileEditorManager.getInstance(project).openFiles
                val openScripts = openFiles.filter { configuration?.scriptModel(it) != null }

                openScripts.forEach {
                    PsiManager.getInstance(project).findFile(it)?.let { psiFile ->
                        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                    }
                }
            }
        }
        // todo: update unindexed roots
        // todo: remove notification, etc..
    }

    fun updateNotification(file: KtFile) {
        val vFile = file.originalFile.virtualFile
        val scriptModel = configuration?.scriptModel(vFile) ?: return

        if (scriptModel.inputs.isUpToDate(project, vFile)) {
            hideNotificationForProjectImport(project)
        } else {
            showNotificationForProjectImport(project)
        }
    }

    override fun isRelated(file: VirtualFile): Boolean {
        if (isGradleKotlinScript(file)) {
            val gradleVersion = getGradleVersion(project)
            if (gradleVersion != null && kotlinDslScriptsModelImportSupported(gradleVersion)) {
                return true
            }
        }

        return false
    }

    override fun clearCaches() {
        configuration = null
    }

    override fun hasCachedConfiguration(file: KtFile): Boolean =
        configuration?.scriptModel(file.originalFile.virtualFile) != null

    override fun getOrLoadConfiguration(virtualFile: VirtualFile, preloadedKtFile: KtFile?): ScriptCompilationConfigurationWrapper? {
        val configuration = configuration
        if (configuration == null) {
            // todo: show notification "Import gradle project"
            return null
        } else {
            return configuration[virtualFile]?.scriptConfiguration
        }
    }

    override fun getFirstScriptsSdk(): Sdk? = configuration?.sdk

    override fun getScriptSdk(file: VirtualFile): Sdk? = configuration?.sdk

    override val updater: ScriptConfigurationUpdater
        get() = object : ScriptConfigurationUpdater {
            override fun ensureUpToDatedConfigurationSuggested(file: KtFile) {
                // do nothing for gradle scripts
            }

            // unused symbol inspection should not initiate loading
            override fun ensureConfigurationUpToDate(files: List<KtFile>): Boolean = true

            override fun suggestToUpdateConfigurationIfOutOfDate(file: KtFile) {
                updateNotification(file)
            }
        }

    override fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope =
        configuration?.get(file)?.classFilesScope ?: GlobalSearchScope.EMPTY_SCOPE

    override fun getAllScriptsDependenciesClassFilesScope(): GlobalSearchScope =
        configuration?.classFilesScope ?: GlobalSearchScope.EMPTY_SCOPE

    override fun getAllScriptDependenciesSourcesScope(): GlobalSearchScope =
        configuration?.sourcesScope ?: GlobalSearchScope.EMPTY_SCOPE

    override fun getAllScriptsDependenciesClassFiles(): List<VirtualFile> =
        configuration?.classFiles ?: listOf()

    override fun getAllScriptDependenciesSources(): List<VirtualFile> =
        configuration?.sources ?: listOf()

    companion object {
        fun getInstance(project: Project): GradleScriptingSupport {
            return ScriptingSupport.SCRIPTING_SUPPORT.getPoint(project).extensionList.firstIsInstance()
        }
    }
}