/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration

import com.intellij.ProjectTopics
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.core.script.*
import org.jetbrains.kotlin.idea.core.script.configuration.cache.*
import org.jetbrains.kotlin.idea.core.script.configuration.cache.ScriptConfigurationFileAttributeCache
import org.jetbrains.kotlin.idea.core.script.configuration.listener.ScriptConfigurationUpdater
import org.jetbrains.kotlin.idea.core.script.configuration.loader.DefaultScriptConfigurationLoader
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptConfigurationLoader
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptConfigurationLoadingContext
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptOutsiderFileConfigurationLoader
import org.jetbrains.kotlin.idea.core.script.configuration.utils.*
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsCache
import org.jetbrains.kotlin.idea.core.script.settings.KotlinScriptingSettings
import org.jetbrains.kotlin.idea.core.util.EDT
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import org.jetbrains.kotlin.scripting.resolve.ScriptReportSink
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.script.experimental.api.ScriptDiagnostic

class DefaultScriptingSupport(project: Project) : DefaultScriptingSupportBase(project) {
    private val backgroundExecutor: BackgroundExecutor =
        if (ApplicationManager.getApplication().isUnitTestMode) TestingBackgroundExecutor(rootsIndexer)
        else DefaultBackgroundExecutor(project, rootsIndexer)

    private val outsiderLoader = ScriptOutsiderFileConfigurationLoader(project)
    private val fileAttributeCache = ScriptConfigurationFileAttributeCache(project)
    private val defaultLoader = DefaultScriptConfigurationLoader(project)
    private val loaders = listOf(outsiderLoader, fileAttributeCache, defaultLoader)

    private val saveLock = ReentrantLock()

    override fun createCache() = object : ScriptConfigurationMemoryCache(project) {
        override fun setLoaded(file: VirtualFile, configurationSnapshot: ScriptConfigurationSnapshot) {
            super.setLoaded(file, configurationSnapshot)
            fileAttributeCache.save(file, configurationSnapshot)
        }
    }

    override fun reloadOutOfDateConfiguration(
        file: KtFile,
        isFirstLoad: Boolean,
        loadEvenWillNotBeApplied: Boolean,
        forceSync: Boolean,
        isPostponedLoad: Boolean
    ) {
        val virtualFile = file.originalFile.virtualFile ?: return

        val autoReloadEnabled = KotlinScriptingSettings.getInstance(project).isAutoReloadEnabled
        val shouldLoad = isFirstLoad || loadEvenWillNotBeApplied || autoReloadEnabled
        if (!shouldLoad) return

        val postponeLoading = isPostponedLoad && !autoReloadEnabled && !isFirstLoad

        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return
        val scriptDefinition = file.findScriptDefinition() ?: return

        val (async, sync) = loaders.partition { it.shouldRunInBackground(scriptDefinition) }

        val syncLoader = sync.firstOrNull { it.loadDependencies(isFirstLoad, file, scriptDefinition, loadingContext) }
        if (syncLoader == null) {
            if (forceSync) {
                loaders.firstOrNull { it.loadDependencies(isFirstLoad, file, scriptDefinition, loadingContext) }
            } else {
                if (postponeLoading) {
                    LoadScriptConfigurationNotificationFactory.showNotification(virtualFile, project) {
                        runAsyncLoaders(file, virtualFile, scriptDefinition, async, isLoadingPostponed = true)
                    }
                } else {
                    runAsyncLoaders(file, virtualFile, scriptDefinition, async, isLoadingPostponed = false)
                }
            }
        }
    }

    private fun runAsyncLoaders(
        file: KtFile,
        virtualFile: VirtualFile,
        scriptDefinition: ScriptDefinition,
        loaders: List<ScriptConfigurationLoader>,
        isLoadingPostponed: Boolean
    ) {
        backgroundExecutor.ensureScheduled(virtualFile) {
            val cached = getCachedConfigurationState(virtualFile)

            val applied = cached?.applied
            if (applied != null && applied.inputs.isUpToDate(project, virtualFile)) {
                // in case user reverted to applied configuration
                suggestOrSaveConfiguration(virtualFile, applied, isLoadingPostponed)
            } else if (cached == null || !cached.isUpToDate(project, virtualFile)) {
                // don't start loading if nothing was changed
                // (in case we checking for up-to-date and loading concurrently)
                val actualIsFirstLoad = cached == null
                loaders.firstOrNull { it.loadDependencies(actualIsFirstLoad, file, scriptDefinition, loadingContext) }
            }
        }
    }

    private val loadingContext = object : ScriptConfigurationLoadingContext {
        override fun getCachedConfiguration(file: VirtualFile): ScriptConfigurationSnapshot? =
            getAppliedConfiguration(file)

        override fun suggestNewConfiguration(file: VirtualFile, newResult: ScriptConfigurationSnapshot) {
            suggestOrSaveConfiguration(file, newResult, false)
        }

        override fun saveNewConfiguration(file: VirtualFile, newResult: ScriptConfigurationSnapshot) {
            suggestOrSaveConfiguration(file, newResult, true)
        }
    }

    private fun suggestOrSaveConfiguration(
        file: VirtualFile,
        newResult: ScriptConfigurationSnapshot,
        skipNotification: Boolean
    ) {
        saveLock.withLock {
            debug(file) { "configuration received = $newResult" }

            setLoadedConfiguration(file, newResult)

            val newConfiguration = newResult.configuration
            if (newConfiguration == null) {
                saveReports(file, newResult.reports)
            } else {
                val old = getCachedConfigurationState(file)
                val oldConfiguration = old?.applied?.configuration
                if (oldConfiguration != null && areSimilar(oldConfiguration, newConfiguration)) {
                    saveReports(file, newResult.reports)
                    file.removeScriptDependenciesNotificationPanel(project)
                } else {
                    val autoReload = skipNotification
                            || oldConfiguration == null
                            || KotlinScriptingSettings.getInstance(project).isAutoReloadEnabled
                            || ApplicationManager.getApplication().isUnitTestModeWithoutScriptLoadingNotification

                    if (autoReload) {
                        if (oldConfiguration != null) {
                            file.removeScriptDependenciesNotificationPanel(project)
                        }
                        saveReports(file, newResult.reports)
                        setAppliedConfiguration(file, newResult)
                    } else {
                        debug(file) {
                            "configuration changed, notification is shown: old = $oldConfiguration, new = $newConfiguration"
                        }

                        // restore reports for applied configuration in case of previous error
                        old?.applied?.reports?.let {
                            saveReports(file, it)
                        }

                        file.addScriptDependenciesNotificationPanel(
                            newConfiguration, project,
                            onClick = {
                                saveReports(file, newResult.reports)
                                file.removeScriptDependenciesNotificationPanel(project)
                                rootsIndexer.transaction {
                                    setAppliedConfiguration(file, newResult)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun saveReports(
        file: VirtualFile,
        newReports: List<ScriptDiagnostic>
    ) {
        val oldReports = IdeScriptReportSink.getReports(file)
        if (oldReports != newReports) {
            debug(file) { "new script reports = $newReports" }

            ServiceManager.getService(project, ScriptReportSink::class.java).attachReports(file, newReports)

            GlobalScope.launch(EDT(project)) {
                if (project.isDisposed) return@launch

                val ktFile = PsiManager.getInstance(project).findFile(file)
                if (ktFile != null) {
                    DaemonCodeAnalyzer.getInstance(project).restart(ktFile)
                }
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
    }
}

abstract class DefaultScriptingSupportBase(val project: Project) : ScriptingSupport {
    protected val rootsIndexer = ScriptClassRootsIndexer(project)

    @Suppress("LeakingThis")
    protected val cache: ScriptConfigurationCache = createCache()

    protected abstract fun createCache(): ScriptConfigurationCache

    /**
     * Will be called on [cache] miss or when [file] is changed.
     * Implementation should initiate loading of [file]'s script configuration and call [setAppliedConfiguration]
     * immediately or in some future
     * (e.g. after user will click "apply context" or/and configuration will be calculated by some background thread).
     *
     * @param isFirstLoad may be set explicitly for optimization reasons (to avoid expensive fs cache access)
     * @param loadEvenWillNotBeApplied may should be set to false only on requests from particular editor, when
     * user can see potential notification and accept new configuration. In other cases this should be `false` since
     * loaded configuration will be just leaved in hidden user notification and cannot be used in any way.
     * @param forceSync should be used in tests only
     * @param isPostponedLoad is used to postspone loading: show a notification for out of date script and start loading when user request
     */
    protected abstract fun reloadOutOfDateConfiguration(
        file: KtFile,
        isFirstLoad: Boolean = getAppliedConfiguration(file.originalFile.virtualFile) == null,
        loadEvenWillNotBeApplied: Boolean = false,
        forceSync: Boolean = false,
        isPostponedLoad: Boolean = false
    )

    fun getCachedConfigurationState(file: VirtualFile?): ScriptConfigurationState? {
        if (file == null) return null
        return cache[file]
    }

    fun getAppliedConfiguration(file: VirtualFile?): ScriptConfigurationSnapshot? =
        getCachedConfigurationState(file)?.applied

    override fun isRelated(file: VirtualFile): Boolean = true

    override fun hasCachedConfiguration(file: KtFile): Boolean =
        getAppliedConfiguration(file.originalFile.virtualFile) != null

    override fun getOrLoadConfiguration(
        virtualFile: VirtualFile,
        preloadedKtFile: KtFile?
    ): ScriptCompilationConfigurationWrapper? {
        val cached = getAppliedConfiguration(virtualFile)
        if (cached != null) return cached.configuration

        val ktFile = project.getKtFile(virtualFile, preloadedKtFile) ?: return null
        rootsIndexer.transaction {
            reloadOutOfDateConfiguration(ktFile, isFirstLoad = true)
        }

        return getAppliedConfiguration(virtualFile)?.configuration
    }

    override val updater: ScriptConfigurationUpdater = object : ScriptConfigurationUpdater {
        override fun ensureUpToDatedConfigurationSuggested(file: KtFile) {
            reloadIfOutOfDate(listOf(file), loadEvenWillNotBeApplied = true, isPostponedLoad = false)
        }

        override fun ensureConfigurationUpToDate(files: List<KtFile>): Boolean {
            return reloadIfOutOfDate(files, loadEvenWillNotBeApplied = false, isPostponedLoad = false)
        }

        override fun suggestToUpdateConfigurationIfOutOfDate(file: KtFile) {
            reloadIfOutOfDate(listOf(file), loadEvenWillNotBeApplied = true, isPostponedLoad = true)
        }
    }

    private fun reloadIfOutOfDate(files: List<KtFile>, loadEvenWillNotBeApplied: Boolean, isPostponedLoad: Boolean): Boolean {
        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return false

        var upToDate = true
        rootsIndexer.transaction {
            files.forEach { file ->
                val virtualFile = file.originalFile.virtualFile
                if (virtualFile != null) {
                    val state = cache[virtualFile]
                    if (state == null || !state.isUpToDate(project, virtualFile, file)) {
                        upToDate = false
                        reloadOutOfDateConfiguration(
                            file,
                            isFirstLoad = state == null,
                            loadEvenWillNotBeApplied = loadEvenWillNotBeApplied,
                            isPostponedLoad = isPostponedLoad
                        )
                    }
                }
            }
        }

        return upToDate
    }

    @TestOnly
    internal fun updateScriptDependenciesSynchronously(file: PsiFile) {
        file.findScriptDefinition() ?: return

        file as? KtFile ?: error("PsiFile $file should be a KtFile, otherwise script dependencies cannot be loaded")

        val virtualFile = file.virtualFile
        if (cache[virtualFile]?.isUpToDate(project, virtualFile, file) == true) return

        rootsIndexer.transaction {
            reloadOutOfDateConfiguration(file, forceSync = true, loadEvenWillNotBeApplied = true)
        }
    }

    protected open fun setAppliedConfiguration(
        file: VirtualFile,
        newConfigurationSnapshot: ScriptConfigurationSnapshot?
    ) {
        rootsIndexer.checkInTransaction()
        val newConfiguration = newConfigurationSnapshot?.configuration
        debug(file) { "configuration changed = $newConfiguration" }

        if (newConfiguration != null) {
            if (hasNotCachedRoots(newConfiguration)) {
                rootsIndexer.markNewRoot(file, newConfiguration)
            }

            cache.setApplied(file, newConfigurationSnapshot)

            clearClassRootsCaches()
        }

        if (file in FileEditorManager.getInstance(project).openFiles) {
            ScriptingSupportHelper.updateHighlighting(project) {
                it == file
            }
        }
    }

    protected fun setLoadedConfiguration(
        file: VirtualFile,
        configurationSnapshot: ScriptConfigurationSnapshot
    ) {
        cache.setLoaded(file, configurationSnapshot)
    }

    private fun hasNotCachedRoots(configuration: ScriptCompilationConfigurationWrapper): Boolean {
        return classpathRoots.hasNotCachedRoots(configuration)
    }

    init {
        val connection = project.messageBus.connect(project)
        connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                clearClassRootsCaches()
            }
        })
    }

    override fun clearCaches() {
        cache.clear()
    }


    ///////////////////
    // ScriptRootsCache

    private val classpathRootsLock = ReentrantLock()

    @Volatile
    private var _classpathRoots: ScriptClassRootsCache? = null
    private val classpathRoots: ScriptClassRootsCache
        get() {
            val value1 = _classpathRoots
            if (value1 != null) return value1

            classpathRootsLock.withLock {
                val value2 = _classpathRoots
                if (value2 != null) return value2

                val value3 = ScriptClassRootsCache(project, cache.allApplied())
                _classpathRoots = value3
                return value3
            }
        }

    private fun clearClassRootsCaches() {
        debug { "class roots caches cleared" }

        classpathRootsLock.withLock {
            _classpathRoots = null
        }

        ScriptingSupportHelper.updateScriptClassRootsCallback(project)
    }

    /**
     * Returns script classpath roots
     * Loads script configuration if classpath roots don't contain [file] yet
     */
    private fun getActualClasspathRoots(file: VirtualFile): ScriptClassRootsCache {
        if (classpathRoots.contains(file)) {
            return classpathRoots
        }

        getOrLoadConfiguration(file)

        return classpathRoots
    }

    override fun getScriptSdk(file: VirtualFile): Sdk? = getActualClasspathRoots(file).getScriptSdk(file)

    override fun getFirstScriptsSdk(): Sdk? = classpathRoots.firstScriptSdk

    override fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope =
        getActualClasspathRoots(file).getScriptDependenciesClassFilesScope(file)

    override fun getAllScriptsDependenciesClassFilesScope(): GlobalSearchScope = classpathRoots.allDependenciesClassFilesScope

    override fun getAllScriptDependenciesSourcesScope(): GlobalSearchScope = classpathRoots.allDependenciesSourcesScope

    override fun getAllScriptsDependenciesClassFiles(): List<VirtualFile> = classpathRoots.allDependenciesClassFiles

    override fun getAllScriptDependenciesSources(): List<VirtualFile> = classpathRoots.allDependenciesSources
}