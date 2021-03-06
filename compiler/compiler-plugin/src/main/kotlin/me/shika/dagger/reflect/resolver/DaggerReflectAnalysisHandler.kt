package me.shika.dagger.reflect.resolver

import me.shika.dagger.reflect.CLASS_SHOULD_BE_INTERFACE
import me.shika.dagger.reflect.DaggerReflectErrors
import me.shika.dagger.reflect.renderer.DaggerReflectRenderer
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.reportFromPlugin
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.classOrObjectRecursiveVisitor
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion.CLASSIFIERS
import org.jetbrains.kotlin.util.prefixIfNot
import java.io.File
import java.io.FileOutputStream

class DaggerReflectAnalysisHandler(private val outputDir: File, private val icOutputDir: File?) : AnalysisHandlerExtension {
    private var generated = false

    override fun doAnalysis(
        project: Project,
        module: ModuleDescriptor,
        projectContext: ProjectContext,
        files: Collection<KtFile>,
        bindingTrace: BindingTrace,
        componentProvider: ComponentProvider
    ): AnalysisResult? {
        if (generated) {
            return null
        }

        outputDir.mkdirs()
        icOutputDir?.mkdirs()

        val manifest = File(icOutputDir, "compiled-manifest.txt")
        val entries = if (manifest.exists()) {
            manifest.bufferedReader().useLines {
                it.map { line ->
                    val chunks = line.split(":")
                    val path = chunks[0]
                    val pkg = chunks[1]
                    val classNames = chunks[2]
                    val classId = ClassId(FqName(pkg), FqName(classNames), false)
                    ICEntry(classId, path)
                }.toSet()
            }
        } else {
            emptySet()
        }
        val toDelete = entries.filter { module.findClassAcrossModuleDependencies(it.classId) == null }.map { it.compiledFilePath }
        toDelete.forEach { File(it).delete() }
        (files as MutableCollection<KtFile>).removeAll { it.virtualFilePath in toDelete }

        val resolveSession = componentProvider.get<ResolveSession>()

        val newFiles = mutableSetOf<ICEntry>()
        for (file in files) {
            if (file.virtualFilePath.startsWith(outputDir.absolutePath)) {
                continue
            }
            file.accept(
                classOrObjectRecursiveVisitor {
                    if (it.annotationEntries.isEmpty()) return@classOrObjectRecursiveVisitor

                    val descriptor = resolveSession.resolveToDescriptor(it)
                    if (descriptor !is ClassDescriptor) return@classOrObjectRecursiveVisitor

                    val path = processDescriptor(descriptor, bindingTrace)?.prefixIfNot(File.separator)
                    if (path != null) {
                        newFiles += ICEntry(
                            descriptor.classId!!,
                            outputDir.absolutePath + path
                        )
                    }
                }
            )
        }

        project.clearCachedOutputFiles(files)

        val output = (entries.filter { it.compiledFilePath !in toDelete } + newFiles).toSet()
        manifest.createNewFile()
        FileOutputStream(manifest, false).bufferedWriter().use {
            output.forEach { entry ->
                val formatted = "${entry.compiledFilePath}:${entry.classId.packageFqName.asString()}:${entry.classId.relativeClassName.asString()}"
                it.write(formatted)
                it.write("\n")
            }
            it.flush()
        }

        generated = true

        return if (bindingTrace.bindingContext.diagnostics.any { it.severity == Severity.ERROR }) {
            AnalysisResult.compilationError(bindingTrace.bindingContext)
        } else {
            AnalysisResult.RetryWithAdditionalRoots(
                bindingContext = bindingTrace.bindingContext,
                moduleDescriptor = module,
                additionalKotlinRoots = newFiles.map { File(it.compiledFilePath) }.toList(),
                additionalJavaRoots = emptyList(),
                addToEnvironment = true
            )
        }
    }

    private fun processDescriptor(descriptor: ClassDescriptor, trace: BindingTrace): String? {
        val componentAnnotation = descriptor.annotations.findAnnotation(COMPONENT_FQ_NAME) ?: return null
        if (descriptor.reportIfClass(trace)) return null

        val renderer = DaggerReflectRenderer(outputDir, descriptor, componentAnnotation)
        val childClasses = descriptor.unsubstitutedMemberScope.getContributedDescriptors(kindFilter = CLASSIFIERS)

        val factory = childClasses.find {
            it is ClassDescriptor && it.annotations.hasAnnotation(COMPONENT_FACTORY_FQ_NAME)
        }

        if (factory != null) {
            factory as ClassDescriptor
            if (factory.reportIfClass(trace)) return null
            return renderer.generateFactory(factory)
        }

        val builder = childClasses.find {
            it is ClassDescriptor && it.annotations.hasAnnotation(COMPONENT_BUILDER_FQ_NAME)
        }

        if (builder != null) {
            builder as ClassDescriptor
            if (builder.reportIfClass(trace)) return null
            return renderer.generateBuilder(builder)
        }

        return renderer.generateDefaultBuilder()
    }

    private fun Project.clearCachedOutputFiles(files: MutableCollection<KtFile>) {
        files.removeIf { file ->
            file.virtualFilePath.startsWith(outputDir.absolutePath).also { removed ->
                if (removed) {
                    dropFileCaches(file)
                }
            }
        }
    }

    private fun Project.dropFileCaches(file: KtFile) {
        (PsiManager.getInstance(this) as PsiManagerImpl).fileManager.setViewProvider(file.virtualFile, null)
    }

    private data class ICEntry(
        val classId: ClassId,
        val compiledFilePath: String
    )

    private fun ClassDescriptor.reportIfClass(trace: BindingTrace): Boolean {
        return if (kind != ClassKind.INTERFACE) {
            trace.reportClass(this) {
                CLASS_SHOULD_BE_INTERFACE.on(it.getClassOrInterfaceKeyword()!!)
            }
            true
        } else {
            false
        }
    }

    private fun BindingTrace.reportClass(descriptor: ClassDescriptor, diagnosticFactory: (KtClass) -> Diagnostic) {
        (descriptor.findPsi() as? KtClass)?.let {
            reportFromPlugin(
                diagnosticFactory(it),
                DaggerReflectErrors
            )
        }
    }

    companion object {
        private val COMPONENT_FQ_NAME = FqName(dagger.Component::class.qualifiedName!!)
        private val COMPONENT_FACTORY_FQ_NAME = FqName(dagger.Component.Factory::class.qualifiedName!!)
        private val COMPONENT_BUILDER_FQ_NAME = FqName(dagger.Component.Builder::class.qualifiedName!!)
    }
}
