/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.NonClasspathDirectoriesScope
import com.intellij.util.containers.SLRUMap
import com.intellij.util.io.URLUtil
import org.jetbrains.kotlin.codegen.inline.getOrPut
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsStorage
import org.jetbrains.kotlin.idea.scripting.gradle.importing.GradleKtsContext
import org.jetbrains.kotlin.idea.util.getProjectJdkTableSafe
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import java.io.File
import java.nio.file.FileSystems

abstract class ScriptClassRootsCache(
    private val project: Project,
    private val roots: ScriptClassRootsStorage.Companion.ScriptClassRoots
) {
    abstract val getter: (VirtualFile) -> ScriptCompilationConfigurationWrapper?

    class Fat(
        val scriptConfiguration: ScriptCompilationConfigurationWrapper,
        val classFilesScope: GlobalSearchScope
    )

    private val memoryCache = SLRUMap<VirtualFile, Fat>(MAX_SCRIPTS_CACHED, MAX_SCRIPTS_CACHED)

    fun get(key: VirtualFile): Fat? {
        return memoryCache.getOrPut(key) {
            val configuration = getter(key) ?: return null

            val scriptSdk = scriptSdk ?: ScriptConfigurationManager.getScriptDefaultSdk(project)
            if (scriptSdk != null && !scriptSdk.isAlreadyIndexed(project)) {
                return Fat(
                    configuration,
                    NonClasspathDirectoriesScope.compose(
                        scriptSdk.rootProvider.getFiles(OrderRootType.CLASSES).toList() + roots.classpathFiles.toVirtualFiles()
                    )
                )

            } else {
                Fat(
                    configuration,
                    NonClasspathDirectoriesScope.compose(roots.classpathFiles.toVirtualFiles())
                )
            }
        }
    }

    private fun String.toVirtualFile(): VirtualFile {
        StandardFileSystems.jar()?.findFileByPath(this + "!/")?.let {
            return it
        }
        StandardFileSystems.local()?.findFileByPath(this)?.let {
            return it
        }


        // TODO: report this somewhere, but do not throw: assert(res != null, { "Invalid classpath entry '$this': exists: ${exists()}, is directory: $isDirectory, is file: $isFile" })

        return VfsUtil.findFile(FileSystems.getDefault().getPath(this), true)!!
    }

    fun Collection<String>.toVirtualFiles() =
        map { it.toVirtualFile() }


    val scriptSdk: Sdk? by lazy {
        return@lazy roots.sdks.firstOrNull()
    }

    val allDependenciesClassFiles by lazy {
        ScriptClassRootsStorage.getInstance(project).loadClasspathRoots()
    }

    val allDependenciesSources by lazy {
        ScriptClassRootsStorage.getInstance(project).loadSourcesRoots()
    }

    val allDependenciesClassFilesScope by lazy {
        NonClasspathDirectoriesScope.compose(allDependenciesClassFiles)
    }

    val allDependenciesSourcesScope by lazy {
        NonClasspathDirectoriesScope.compose(allDependenciesSources)
    }

    fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope {
        return get(file)?.classFilesScope ?: GlobalSearchScope.EMPTY_SCOPE
    }

    fun hasNotCachedRoots(roots: ScriptClassRootsStorage.Companion.ScriptClassRoots): Boolean {
        return !ScriptClassRootsStorage.getInstance(project).containsAll(roots)
    }

    init {
        saveClassRootsToStorage()
    }

    private fun saveClassRootsToStorage() {
        val rootsStorage = ScriptClassRootsStorage.getInstance(project)
        rootsStorage.save(
            roots
        )
    }

    companion object {
        const val MAX_SCRIPTS_CACHED = 50

        private fun toStringValues(prop: Collection<File>): Set<String> {
            return prop.mapNotNull {
                when {
                    it.isDirectory -> it.absolutePath
                    it.isFile -> it.absolutePath + URLUtil.JAR_SEPARATOR
                    else -> null
                }
            }.toSet()
        }

        fun extractRoots(
            project: Project,
            configuration: ScriptCompilationConfigurationWrapper
        ): ScriptClassRootsStorage.Companion.ScriptClassRoots {
            val scriptSdk = getScriptSdkOfDefault(configuration.javaHome, project)
            if (scriptSdk != null && !scriptSdk.isAlreadyIndexed(project)) {
                return ScriptClassRootsStorage.Companion.ScriptClassRoots(
                    toStringValues(configuration.dependenciesClassPath),
                    toStringValues(configuration.dependenciesSources),
                    setOf(scriptSdk)
                )
            }

            return ScriptClassRootsStorage.Companion.ScriptClassRoots(
                toStringValues(configuration.dependenciesClassPath),
                toStringValues(configuration.dependenciesSources),
                emptySet()
            )
        }

        fun getScriptSdkOfDefault(javaHomeStr: File?, project: Project): Sdk? {
            return getScriptSdk(javaHomeStr) ?: ScriptConfigurationManager.getScriptDefaultSdk(project)
        }

        fun getScriptSdk(javaHomeStr: File?): Sdk? {
            // workaround for mismatched gradle wrapper and plugin version
            val javaHome = try {
                javaHomeStr?.let { VfsUtil.findFileByIoFile(it, true) }
            } catch (e: Throwable) {
                null
            } ?: return null

            return getProjectJdkTableSafe().allJdks.find { it.homeDirectory == javaHome }
        }

        private fun Sdk.isAlreadyIndexed(project: Project): Boolean {
            return ModuleManager.getInstance(project).modules.any { ModuleRootManager.getInstance(it).sdk == this }
        }

    }
}

internal class GradleClassRootsCache(
    context: GradleKtsContext,
    classFilePath: MutableSet<String>,
    sourcePath: MutableSet<String>,
    override val getter: (VirtualFile) -> ScriptCompilationConfigurationWrapper?
) : ScriptClassRootsCache(context.project, extractRoots(context, classFilePath, sourcePath)) {

    companion object {
        fun extractRoots(
            context: GradleKtsContext,
            classFilePath: MutableSet<String>,
            sourcePath: MutableSet<String>
        ): ScriptClassRootsStorage.Companion.ScriptClassRoots {
            return ScriptClassRootsStorage.Companion.ScriptClassRoots(
                classFilePath,
                sourcePath,
                getScriptSdk(context.javaHome)?.let { setOf(it) } ?: setOf()
            )
        }
    }

}