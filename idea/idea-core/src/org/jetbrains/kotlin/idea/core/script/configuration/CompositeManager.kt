/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.idea.core.script.KotlinScriptDependenciesClassFinder
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.core.script.ScriptDependenciesModificationTracker
import org.jetbrains.kotlin.idea.core.script.configuration.listener.ScriptChangesNotifier
import org.jetbrains.kotlin.idea.core.script.configuration.listener.ScriptConfigurationUpdater
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptConfigurationLoader
import org.jetbrains.kotlin.idea.core.script.configuration.utils.getKtFile
import org.jetbrains.kotlin.idea.core.util.EDT
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.isNonScript
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper

interface ScriptingSupport {
    fun isRelated(file: VirtualFile): Boolean

    fun clearCaches()
    fun hasCachedConfiguration(file: KtFile): Boolean
    fun getOrLoadConfiguration(virtualFile: VirtualFile, preloadedKtFile: KtFile? = null): ScriptCompilationConfigurationWrapper?

    val updater: ScriptConfigurationUpdater

    fun getFirstScriptsSdk(): Sdk?
    fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope

    fun getScriptSdk(file: VirtualFile): Sdk?
    fun getAllScriptsDependenciesClassFilesScope(): GlobalSearchScope
    fun getAllScriptDependenciesSourcesScope(): GlobalSearchScope
    fun getAllScriptsDependenciesClassFiles(): List<VirtualFile>
    fun getAllScriptDependenciesSources(): List<VirtualFile>

    companion object {
        val SCRIPTING_SUPPORT: ExtensionPointName<ScriptingSupport> =
            ExtensionPointName.create("org.jetbrains.kotlin.scripting.idea.scriptingSupport")
    }
}

class CompositeManager(val project: Project) : ScriptConfigurationManager {
    @Suppress("unused")
    private val notifier = ScriptChangesNotifier(project, updater)

    private val managers = ScriptingSupport.SCRIPTING_SUPPORT.getPoint(project).extensionList

    private fun getRelatedManager(file: VirtualFile): ScriptingSupport = managers.first { it.isRelated(file) }
    private fun getRelatedManager(file: KtFile): ScriptingSupport =
        getRelatedManager(file.originalFile.virtualFile)

    private fun getOrLoadConfiguration(
        virtualFile: VirtualFile,
        preloadedKtFile: KtFile? = null
    ): ScriptCompilationConfigurationWrapper? =
        getRelatedManager(virtualFile).getOrLoadConfiguration(virtualFile, preloadedKtFile)

    override fun getConfiguration(file: KtFile) = getOrLoadConfiguration(file.originalFile.virtualFile, file)

    override fun hasConfiguration(file: KtFile): Boolean =
        getRelatedManager(file).hasCachedConfiguration(file)

    override val updater: ScriptConfigurationUpdater
        get() = object : ScriptConfigurationUpdater {
            override fun ensureUpToDatedConfigurationSuggested(file: KtFile) =
                getRelatedManager(file).updater.ensureUpToDatedConfigurationSuggested(file)

            override fun ensureConfigurationUpToDate(files: List<KtFile>): Boolean =
                files.groupBy { getRelatedManager(it) }.all { (manager, files) ->
                    manager.updater.ensureConfigurationUpToDate(files)
                }

            override fun suggestToUpdateConfigurationIfOutOfDate(file: KtFile) =
                getRelatedManager(file).updater.suggestToUpdateConfigurationIfOutOfDate(file)
        }

    override fun getScriptSdk(file: VirtualFile): Sdk? =
        getRelatedManager(file).getScriptSdk(file)

    override fun getFirstScriptsSdk(): Sdk? {
        managers.forEach {
            it.getFirstScriptsSdk()?.let { sdk -> return sdk }
        }

        return null
    }

    override fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope =
        getRelatedManager(file).getScriptDependenciesClassFilesScope(file)

    override fun getAllScriptsDependenciesClassFilesScope(): GlobalSearchScope =
        GlobalSearchScope.union(managers.map { it.getAllScriptsDependenciesClassFilesScope() })

    override fun getAllScriptDependenciesSourcesScope(): GlobalSearchScope =
        GlobalSearchScope.union(managers.map { it.getAllScriptDependenciesSourcesScope() })

    override fun getAllScriptsDependenciesClassFiles(): List<VirtualFile> =
        managers.flatMap { it.getAllScriptsDependenciesClassFiles() }

    override fun getAllScriptDependenciesSources(): List<VirtualFile> =
        managers.flatMap { it.getAllScriptDependenciesSources() }

    ///////////////////
    // Should be removed
    //

    override fun forceReloadConfiguration(file: VirtualFile, loader: ScriptConfigurationLoader): ScriptCompilationConfigurationWrapper? {
        // This seems to be Gradle only and should be named reloadOutOfProjectScriptConfiguration
        TODO("Not yet implemented")
    }

    ///////////////////
    // Adapters for deprecated API
    //

    @Deprecated("Use getScriptClasspath(KtFile) instead")
    override fun getScriptClasspath(file: VirtualFile): List<VirtualFile> {
        val ktFile = project.getKtFile(file) ?: return emptyList()
        return getScriptClasspath(ktFile)
    }

    override fun getScriptClasspath(file: KtFile): List<VirtualFile> =
        ScriptConfigurationManager.toVfsRoots(getConfiguration(file)?.dependenciesClassPath.orEmpty())

    ///////////////////
    // ScriptRootsCache

    private fun clearCaches() {
        managers.forEach {
            it.clearCaches()
        }
    }

    override fun clearConfigurationCachesAndRehighlight() {
        ScriptDependenciesModificationTracker.getInstance(project).incModificationCount()

        clearCaches()

        if (project.isOpen) {
            ScriptingSupportHelper.updateHighlighting(project) {
                it.isNonScript()
            }
        }
    }
}

object ScriptingSupportHelper {
    fun updateHighlighting(project: Project, filter: (VirtualFile) -> Boolean) {
        val openFiles = FileEditorManager.getInstance(project).openFiles
        val openedScripts = openFiles.filter { filter(it) }

        if (openedScripts.isEmpty()) return

        GlobalScope.launch(EDT(project)) {
            if (project.isDisposed) return@launch

            openedScripts.forEach {
                PsiManager.getInstance(project).findFile(it)?.let { psiFile ->
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                }
            }
        }
    }

    fun updateScriptClassRootsCallback(project: Project) {
        val kotlinScriptDependenciesClassFinder =
            Extensions.getArea(project).getExtensionPoint(PsiElementFinder.EP_NAME).extensions
                .filterIsInstance<KotlinScriptDependenciesClassFinder>()
                .single()

        kotlinScriptDependenciesClassFinder.clearCache()

        ScriptDependenciesModificationTracker.getInstance(project).incModificationCount()
    }

}