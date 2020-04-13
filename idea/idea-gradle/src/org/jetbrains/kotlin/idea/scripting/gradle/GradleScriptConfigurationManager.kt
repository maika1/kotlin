/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.core.script.configuration.ScriptingSupport
import org.jetbrains.kotlin.idea.core.script.configuration.ScriptingSupportHelper
import org.jetbrains.kotlin.idea.core.script.configuration.listener.ScriptConfigurationUpdater
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsIndexer
import org.jetbrains.kotlin.idea.scripting.gradle.importing.GradleKtsContext
import org.jetbrains.kotlin.idea.scripting.gradle.importing.KotlinDslScriptModel
import org.jetbrains.kotlin.idea.scripting.gradle.importing.toScriptConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

private class Configuration(
    val context: GradleKtsContext,
    models: List<KotlinDslScriptModel>
) {
    val scripts = models.associateBy { it.file }
    val classFilePath = models.flatMapTo(mutableSetOf()) { it.classPath }
    val sourcePath = models.flatMapTo(mutableSetOf()) { it.sourcePath }

    fun scriptModel(file: VirtualFile): KotlinDslScriptModel? {
        return scripts[FileUtil.toSystemDependentName(file.path)]
    }

    val classRootsCache = GradleClassRootsCache(context, classFilePath, sourcePath) {
        val model = scriptModel(it)
        model?.toScriptConfiguration(context)
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

        configurationChangedCallback(old, newConfiguration)
    }

    private fun configurationChangedCallback(
        old: Configuration?,
        newConfiguration: Configuration
    ) {
        if (shouldReindex(old, newConfiguration)) {
            rootsIndexer.startIndexing()
            ScriptingSupportHelper.updateScriptClassRootsCallback(project)

            // todo when there are changes, not only roots
            ScriptingSupportHelper.updateHighlighting(project) {
                configuration?.scriptModel(it) != null
            }
        }

        hideNotificationForProjectImport(project)
    }

    private fun shouldReindex(
        old: Configuration?,
        new: Configuration
    ): Boolean {
        if (old == null) return true

        if (new.classRootsCache.hasNotCachedRoots(new.roots)) return true

        //todo
        if (new.classRootsCache.scriptSdk?.isAlreadyIndexed() == false) return true

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
        val newConfiguration = Configuration(GradleKtsContext(project, javaHome), models)

        configuration = newConfiguration

        configurationChangedCallback(null, newConfiguration)
    }

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            load()
        }
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
            return configuration.classRootsCache.get(virtualFile)?.scriptConfiguration
        }
    }

    // todo: if project sdk changed we should reload classRoots
    override fun getFirstScriptsSdk(): Sdk? = configuration?.classRootsCache?.scriptSdk

    override fun getScriptSdk(file: VirtualFile): Sdk? = configuration?.classRootsCache?.scriptSdk

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
        configuration?.classRootsCache?.getScriptDependenciesClassFilesScope(file) ?: GlobalSearchScope.EMPTY_SCOPE

    override fun getAllScriptsDependenciesClassFilesScope(): GlobalSearchScope =
        configuration?.classRootsCache?.allDependenciesClassFilesScope ?: GlobalSearchScope.EMPTY_SCOPE

    override fun getAllScriptDependenciesSourcesScope(): GlobalSearchScope =
        configuration?.classRootsCache?.allDependenciesSourcesScope ?: GlobalSearchScope.EMPTY_SCOPE

    override fun getAllScriptsDependenciesClassFiles(): List<VirtualFile> =
        configuration?.classRootsCache?.allDependenciesClassFiles ?: listOf()

    override fun getAllScriptDependenciesSources(): List<VirtualFile> =
        configuration?.classRootsCache?.allDependenciesSources ?: listOf()

    companion object {
        fun getInstance(project: Project): GradleScriptingSupport {
            return ScriptingSupport.SCRIPTING_SUPPORT.getPoint(project).extensionList.firstIsInstance()
        }
    }
}