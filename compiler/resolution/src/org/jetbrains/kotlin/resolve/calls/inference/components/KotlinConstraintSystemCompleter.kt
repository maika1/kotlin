/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.builtins.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.resolve.calls.components.transformToResolvedLambda
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemBuilder
import org.jetbrains.kotlin.resolve.calls.inference.NewConstraintSystem
import org.jetbrains.kotlin.resolve.calls.inference.model.*
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.model.KotlinTypeMarker
import org.jetbrains.kotlin.types.model.TypeConstructorMarker
import org.jetbrains.kotlin.types.model.TypeVariableMarker
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection
import org.jetbrains.kotlin.types.typeUtil.builtIns
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class KotlinConstraintSystemCompleter(
    private val resultTypeResolver: ResultTypeResolver,
    val variableFixationFinder: VariableFixationFinder,
) {
    enum class ConstraintSystemCompletionMode {
        FULL,
        PARTIAL
    }

    interface Context : VariableFixationFinder.Context, ResultTypeResolver.Context {
        override val notFixedTypeVariables: Map<TypeConstructorMarker, VariableWithConstraints>

        override val postponedTypeVariables: List<TypeVariableMarker>

        // type can be proper if it not contains not fixed type variables
        fun canBeProper(type: KotlinTypeMarker): Boolean

        fun containsOnlyFixedOrPostponedVariables(type: KotlinTypeMarker): Boolean

        // mutable operations
        fun addError(error: KotlinCallDiagnostic)

        fun fixVariable(variable: TypeVariableMarker, resultType: KotlinTypeMarker, atom: ResolvedAtom?)
    }

    fun runCompletion(
        c: Context,
        completionMode: ConstraintSystemCompletionMode,
        topLevelAtoms: List<ResolvedAtom>,
        topLevelType: UnwrappedType,
        diagnosticsHolder: KotlinDiagnosticsHolder,
        analyze: (PostponedResolvedAtom) -> Unit
    ) {
        runCompletion(
            c,
            completionMode,
            topLevelAtoms,
            topLevelType,
            diagnosticsHolder,
            collectVariablesFromContext = false,
            analyze = analyze
        )
    }

    fun completeConstraintSystem(
        c: Context,
        topLevelType: UnwrappedType,
        topLevelAtoms: List<ResolvedAtom>,
        diagnosticsHolder: KotlinDiagnosticsHolder
    ) {
        runCompletion(
            c,
            ConstraintSystemCompletionMode.FULL,
            topLevelAtoms,
            topLevelType,
            diagnosticsHolder,
            collectVariablesFromContext = true,
        ) {
            error("Shouldn't be called in complete constraint system mode")
        }
    }

    private fun Context.fixVariablesInsideConstraints(
        typeVariable: TypeVariableTypeConstructor,
        topLevelAtoms: List<ResolvedAtom>,
        completionMode: ConstraintSystemCompletionMode,
        topLevelType: UnwrappedType,
        typeVariablesSeen: MutableSet<TypeVariableTypeConstructor>
    ): Boolean? {
        if (typeVariablesSeen.contains(typeVariable)) return null

        typeVariablesSeen.add(typeVariable)

        val notFixedTypeVariable = notFixedTypeVariables[typeVariable] ?: return null
        if (notFixedTypeVariable.constraints.size == 0) return null

        return notFixedTypeVariable.constraints.toMutableList().map { constraint ->
            when {
                constraint.type.argumentsCount() > 0 -> {
                    val tt = if ((constraint.type as KotlinType).isBuiltinFunctionalType) {
                        constraint.type.arguments.map { it.type } // dropLast(1).
                    } else {
                        constraint.type.arguments.map { it.type }
                    }
                    fixVariablesInsideTypes(
                        tt,
                        topLevelAtoms,
                        completionMode,
                        topLevelType,
                        typeVariablesSeen
                    )
                }
                constraint.type.lowerBoundIfFlexible().typeConstructor() is TypeVariableTypeConstructor -> {
                    fixVariablesInsideTypes(listOf(constraint.type as KotlinType), topLevelAtoms, completionMode, topLevelType, typeVariablesSeen)
                }
                else -> false
            }
        }.all { it }
    }

    private fun Context.fixVariablesInsideTypes(
        types: List<KotlinType>,
        topLevelAtoms: List<ResolvedAtom>,
        completionMode: ConstraintSystemCompletionMode,
        topLevelType: UnwrappedType,
        typeVariablesSeen: MutableSet<TypeVariableTypeConstructor> = mutableSetOf()
    ): Boolean =
        types.map { type ->
            val typeConstructor = type.constructor
            if (typeConstructor is TypeVariableTypeConstructor && notFixedTypeVariables.containsKey(typeConstructor)) {
                val isFixed = fixVariablesInsideConstraints(typeConstructor, topLevelAtoms, completionMode, topLevelType, typeVariablesSeen)

                val hasProperConstraint = variableFixationFinder.findFirstVariableForFixation(
                    this, listOf(typeConstructor), getOrderedNotAnalyzedPostponedArguments(topLevelAtoms), completionMode, topLevelType
                )?.hasProperConstraint == true

                if (hasProperConstraint) {
                    fixVariable(this, notFixedTypeVariables.getValue(typeConstructor), topLevelAtoms)
                    isFixed != null
                } else {
                    false
                }
            } else if (type.arguments.isNotEmpty()) {
                val tt = if (type.isBuiltinFunctionalType) {
                    type.arguments.map { it.type } // dropLast(1).
                } else {
                    type.arguments.map { it.type }
                }

                fixVariablesInsideTypes(tt, topLevelAtoms, completionMode, topLevelType, typeVariablesSeen)
            } else {
                false
            }
        }.all { it } && types.size != 0

    private fun Context.fixVariablesInsideFunctionTypeArguments(
        postponedArguments: List<PostponedResolvedAtom>,
        topLevelAtoms: List<ResolvedAtom>,
        completionMode: ConstraintSystemCompletionMode,
        topLevelType: UnwrappedType
    ) = postponedArguments.map { argument ->
        val expectedType = argument.expectedType ?: return@map false
        val isExpectedTypeFunctionTypeWithArguments = expectedType.isBuiltinFunctionalType && expectedType.arguments.size > 1

        if (isExpectedTypeFunctionTypeWithArguments) {
            fixVariablesInsideTypes(expectedType.arguments.dropLast(1).map { it.type }, topLevelAtoms, completionMode, topLevelType)
        } else if (expectedType.isBuiltinFunctionalType) {
            false
        } else {
            fixVariablesInsideTypes(listOf(expectedType), topLevelAtoms, completionMode, topLevelType)
        }
    }.all { it } && postponedArguments.size != 0

    fun Context.foundFunctionTypes(
        v: VariableWithConstraints,
        typeVariablesSeen: MutableSet<TypeVariableTypeConstructor> = mutableSetOf()
    ): KotlinType? {
        if (typeVariablesSeen.contains(v.typeVariable.freshTypeConstructor() as TypeVariableTypeConstructor)) return null
        typeVariablesSeen.add(v.typeVariable.freshTypeConstructor() as TypeVariableTypeConstructor)
        return v.constraints.mapNotNull {
            if ((it.type as KotlinType).isBuiltinFunctionalType && (it.kind == ConstraintKind.EQUALITY || it.kind == ConstraintKind.LOWER)) {
                it.type
            } else if (it.type.constructor in notFixedTypeVariables) {
                foundFunctionTypes(notFixedTypeVariables[it.type.constructor]!!, typeVariablesSeen)
            } else null
        }.firstOrNull()
    }

    private fun runCompletion(
        c: Context,
        completionMode: ConstraintSystemCompletionMode,
        topLevelAtoms: List<ResolvedAtom>,
        topLevelType: UnwrappedType,
        diagnosticsHolder: KotlinDiagnosticsHolder,
        collectVariablesFromContext: Boolean,
        analyze: (PostponedResolvedAtom) -> Unit
    ) {
        // Kt32250 - валится из-за фиксации перед преобразованием ниже в while
        var isFixed: Boolean = false

        while (true) {
            val postponedArguments = getOrderedNotAnalyzedPostponedArguments(topLevelAtoms)
            if (postponedArguments.isEmpty()) break
            val argument = postponedArguments.first()
            val expectedType = argument.expectedType

            if (expectedType != null) {
                if (expectedType.constructor !is TypeVariableTypeConstructor) {
                    isFixed = c.fixVariablesInsideFunctionTypeArguments(postponedArguments, topLevelAtoms, completionMode, topLevelType)
                } else {
                    val s = variableFixationFinder.findFirstVariableForFixation(
                        c,
                        listOf(expectedType.constructor),
                        getOrderedNotAnalyzedPostponedArguments(topLevelAtoms),
                        completionMode,
                        topLevelType
                    )
                    if (s != null) {
                        val f = c.foundFunctionTypes(c.notFixedTypeVariables[expectedType.constructor]!!)
                        val functionTypeParametersCount = (f?.arguments?.size ?: 1) - 1

                        if (f != null && !f.isExtensionFunctionType) {
                            isFixed = c.fixVariablesInsideFunctionTypeArguments(postponedArguments, topLevelAtoms, completionMode, topLevelType)
                        }
                        val t = c.resolveLambdaByAdditionalConditions(
                            s,
                            postponedArguments,
                            diagnosticsHolder,
                            analyze,
                            variableFixationFinder,
                            functionTypeParametersCount
                        ) {
                            analyze(it)
                        }
                        if (f == null) {
                            isFixed = c.fixVariablesInsideFunctionTypeArguments(postponedArguments, topLevelAtoms, completionMode, topLevelType)
                        }

                        if (!t.isNullOrEmpty()) continue
                    }
                }
            } else {
                isFixed = c.fixVariablesInsideFunctionTypeArguments(postponedArguments, topLevelAtoms, completionMode, topLevelType)
            }

            break
        }

        while (true) {
            if (analyzePostponeArgumentIfPossible(c, topLevelAtoms, analyze)) continue

            val postponedArguments = getOrderedNotAnalyzedPostponedArguments(topLevelAtoms)

            if (postponedArguments.isEmpty()) break

            val argument = postponedArguments.first()

            if (!isFixed && !c.fixVariablesInsideFunctionTypeArguments(postponedArguments, topLevelAtoms, completionMode, topLevelType)) {
                break
            }

            if (!argument.analyzed) {
                analyze(argument)
            }
        }

        while (true) {
            val allTypeVariables = getOrderedAllTypeVariables(c, collectVariablesFromContext, topLevelAtoms)
            val postponedKtPrimitives = getOrderedNotAnalyzedPostponedArguments(topLevelAtoms)
            val variableForFixation =
                variableFixationFinder.findFirstVariableForFixation(
                    c, allTypeVariables, postponedKtPrimitives, completionMode, topLevelType
                ) ?: break

            // completionMode == ConstraintSystemCompletionMode.FULL -- ?
            if (variableForFixation.hasProperConstraint || completionMode == ConstraintSystemCompletionMode.FULL) {
                val variableWithConstraints = c.notFixedTypeVariables.getValue(variableForFixation.variable)

                if (variableForFixation.hasProperConstraint)
                    fixVariable(c, variableWithConstraints, topLevelAtoms)
                else
                    processVariableWhenNotEnoughInformation(c, variableWithConstraints, topLevelAtoms)

                continue
            }

            break
        }

        if (completionMode == ConstraintSystemCompletionMode.FULL) {
            // force resolution for all not-analyzed argument's
            getOrderedNotAnalyzedPostponedArguments(topLevelAtoms).forEach(analyze)
            if (c.notFixedTypeVariables.isNotEmpty() && c.postponedTypeVariables.isEmpty()) {
                runCompletion(c, completionMode, topLevelAtoms, topLevelType, diagnosticsHolder, analyze)
            }
        }
    }

    /*
     * returns true -> analyzed
     */
    private fun Context.resolveLambdaByAdditionalConditions(
        variableForFixation: VariableFixationFinder.VariableForFixation,
        topLevelAtoms: List<ResolvedAtom>,
        diagnosticsHolder: KotlinDiagnosticsHolder,
        analyze: (PostponedResolvedAtom) -> Unit,
        fixationFinder: VariableFixationFinder,
        functionTypeParametersCount: Int,
        callback: (PostponedResolvedAtom) -> Unit
    ): List<PostponedResolvedAtom>? {
        val postponedArguments = getOrderedNotAnalyzedPostponedArguments(topLevelAtoms)

        val f = resolveLambdaOrCallableReferenceWithTypeVariableAsExpectedType(
            variableForFixation,
            postponedArguments,
            diagnosticsHolder,
            analyze,
            functionTypeParametersCount,
            callback
        )

        val s = resolveLambdaWhichIsReturnArgument(postponedArguments, diagnosticsHolder, analyze, fixationFinder, functionTypeParametersCount, callback)

        if (f == null) return s
        if (s == null) return f

        return s + f
    }

    /*
     * returns true -> analyzed
     */
    private fun Context.resolveLambdaWhichIsReturnArgument(
        postponedArguments: List<PostponedResolvedAtom>,
        diagnosticsHolder: KotlinDiagnosticsHolder,
        analyze: (PostponedResolvedAtom) -> Unit,
        fixationFinder: VariableFixationFinder,
        functionTypeParametersCount: Int,
        callback: (PostponedResolvedAtom) -> Unit
    ): List<PostponedResolvedAtom>? {
        if (this !is NewConstraintSystem) return null

        return postponedArguments.mapNotNull { postponedAtom ->
            val isReturnArgumentOfAnotherLambda = postponedAtom is LambdaWithTypeVariableAsExpectedTypeAtom && postponedAtom.isReturnArgumentOfAnotherLambda

            val atomExpectedType = postponedAtom.expectedType

            val shouldAnalyzeByPresenceLambdaAsReturnArgument =
                isReturnArgumentOfAnotherLambda && atomExpectedType != null &&
                        with(fixationFinder) { variableHasTrivialOrNonProperConstraints(atomExpectedType.constructor) }

            if (!shouldAnalyzeByPresenceLambdaAsReturnArgument)
                return@mapNotNull null

            val expectedTypeVariable =
                atomExpectedType?.constructor?.takeIf { it in this.getBuilder().currentStorage().allTypeVariables } ?: return@mapNotNull null

            val s = preparePostponedAtom(expectedTypeVariable.typeForTypeVariable(), postponedAtom, expectedTypeVariable.builtIns, diagnosticsHolder, functionTypeParametersCount) ?: return null

            callback(s)

            s
        }
    }

    /*
     * returns true -> analyzed
     */
    fun Context.resolveLambdaOrCallableReferenceWithTypeVariableAsExpectedType(
        variableForFixation: VariableFixationFinder.VariableForFixation,
        postponedArguments: List<PostponedResolvedAtom>,
        diagnosticsHolder: KotlinDiagnosticsHolder,
        analyze: (PostponedResolvedAtom) -> Unit,
        functionTypeParametersCount: Int,
        callback: (PostponedResolvedAtom) -> Unit
    ): List<PostponedResolvedAtom>? {
        if (this !is NewConstraintSystem) return null

        val variable = variableForFixation.variable as? TypeConstructor ?: return null
        val hasProperAtom = postponedArguments.any {
            when (it) {
                is LambdaWithTypeVariableAsExpectedTypeAtom,
                is PostponedCallableReferenceAtom -> it.expectedType?.constructor == variable
                else -> false
            }
        }

        return postponedArguments.mapNotNull { postponedAtom ->
            val expectedTypeAtom = postponedAtom.expectedType
            val expectedTypeVariable =
                expectedTypeAtom?.constructor?.takeIf { it in this.getBuilder().currentStorage().allTypeVariables } ?: variable

            val shouldAnalyzeByEqualityExpectedTypeToVariable =
                hasProperAtom || !variableForFixation.hasProperConstraint || variableForFixation.hasOnlyTrivialProperConstraint

            if (!shouldAnalyzeByEqualityExpectedTypeToVariable)
                return@mapNotNull null

            val z = preparePostponedAtom(expectedTypeVariable.typeForTypeVariable(), postponedAtom, variable.builtIns, diagnosticsHolder, functionTypeParametersCount)
                ?: return@mapNotNull null

            callback(z)

            z
        }
    }

    private fun Context.preparePostponedAtom(
        expectedTypeVariable: UnwrappedType,
        postponedAtom: PostponedResolvedAtom,
        builtIns: KotlinBuiltIns,
        diagnosticsHolder: KotlinDiagnosticsHolder,
        functionTypeParametersCount: Int
    ): PostponedResolvedAtom? {
        val csBuilder = (this as? NewConstraintSystem)?.getBuilder() ?: return null

        return when (postponedAtom) {
            is PostponedCallableReferenceAtom -> postponedAtom.preparePostponedAtomWithTypeVariableAsExpectedType(
                this, csBuilder, expectedTypeVariable.constructor,
                parameterTypes = null,
                isSuitable = KotlinType::isBuiltinFunctionalTypeOrSubtype,
                typeVariableCreator = { TypeVariableForCallableReferenceReturnType(builtIns, "_Q") },
                newAtomCreator = { returnVariable, expectedType ->
                    CallableReferenceWithTypeVariableAsExpectedTypeAtom(postponedAtom.atom, expectedType, returnVariable).also {
                        postponedAtom.setAnalyzedResults(null, listOf(it))
                    }
                },
                functionTypeParametersCount = functionTypeParametersCount
            )
            is LambdaWithTypeVariableAsExpectedTypeAtom -> postponedAtom.preparePostponedAtomWithTypeVariableAsExpectedType(
                this, csBuilder, expectedTypeVariable.constructor,
                parameterTypes = postponedAtom.atom.parametersTypes,
                isSuitable = KotlinType::isBuiltinFunctionalType,
                typeVariableCreator = { TypeVariableForLambdaReturnType(postponedAtom.atom, builtIns, "_R") },
                newAtomCreator = { returnVariable, expectedType ->
                    postponedAtom.transformToResolvedLambda(csBuilder, diagnosticsHolder, expectedType, returnVariable)
                },
                functionTypeParametersCount = functionTypeParametersCount
            )
            else -> null
        }
    }

    fun <T : PostponedResolvedAtom, V : NewTypeVariable> T.preparePostponedAtomWithTypeVariableAsExpectedType(
        c: Context,
        csBuilder: ConstraintSystemBuilder,
        variable: TypeConstructor,
        parameterTypes: Array<out KotlinType?>?,
        isSuitable: KotlinType.() -> Boolean,
        typeVariableCreator: () -> V,
        newAtomCreator: (V, SimpleType) -> PostponedResolvedAtom,
        functionTypeParametersCount: Int
    ): PostponedResolvedAtom {
        if (variable !in c.notFixedTypeVariables) return this
        val functionalType = resultTypeResolver.findResultType(
            c,
            c.notFixedTypeVariables.getValue(variable),
            TypeVariableDirectionCalculator.ResolveDirection.TO_SUPERTYPE
        ) as KotlinType
        val isExtensionFunction = functionalType.isExtensionFunctionType
        val isExtensionFunctionWithReceiverAsDeclaredParameter =
            isExtensionFunction && functionalType.arguments.size - 1 == parameterTypes?.filterNotNull()?.size
        if (parameterTypes?.all { type -> type != null } == true && (!isExtensionFunction || isExtensionFunctionWithReceiverAsDeclaredParameter)) return this
        if (!functionalType.isSuitable() && functionTypeParametersCount == 0) return this
        val returnVariable = typeVariableCreator()
        csBuilder.registerVariable(returnVariable)

        var arguments = mutableListOf<TypeProjection>()
        if (functionTypeParametersCount > 0 && functionalType.arguments.size != functionTypeParametersCount + 1) {
            for (i in 0 until functionTypeParametersCount) {
                val arg = typeVariableCreator()
                csBuilder.registerVariable(arg)
                arguments.add(arg.defaultType.asTypeProjection())
            }
        } else {
            arguments = functionalType.arguments.dropLast(1).toMutableList()
        }

        val expectedType = if (!functionalType.isSuitable()) {
            createFunctionType(
                functionalType.builtIns,
                Annotations.EMPTY,
                null,
                arguments.map { it.type },
                null,
                returnVariable.defaultType
            )
        } else {
            KotlinTypeFactory.simpleType(
                functionalType.annotations,
                functionalType.constructor,
                arguments + returnVariable.defaultType.asTypeProjection(),
                functionalType.isMarkedNullable
            )
        }

        csBuilder.addSubtypeConstraint(
            expectedType,
            variable.typeForTypeVariable(),
            ArgumentConstraintPosition(atom as KotlinCallArgument)
        )
        return newAtomCreator(returnVariable, expectedType)
    }

    // true if we do analyze
    private fun analyzePostponeArgumentIfPossible(
        c: Context,
        topLevelAtoms: List<ResolvedAtom>,
        analyze: (PostponedResolvedAtom) -> Unit
    ): Boolean {
        for (argument in getOrderedNotAnalyzedPostponedArguments(topLevelAtoms)) {
            ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()
            if (canWeAnalyzeIt(c, argument)) {
                analyze(argument)
                return true
            }
        }
        return false
    }

    private fun getOrderedAllTypeVariables(
        c: Context,
        collectVariablesFromContext: Boolean,
        topLevelAtoms: List<ResolvedAtom>
    ): List<TypeConstructorMarker> {
        if (collectVariablesFromContext) return c.notFixedTypeVariables.keys.toList()

        fun ResolvedAtom.process(to: LinkedHashSet<TypeConstructor>) {
            val typeVariables = when (this) {
                is ResolvedCallAtom -> freshVariablesSubstitutor.freshVariables
                is CallableReferenceWithTypeVariableAsExpectedTypeAtom -> mutableListOf<NewTypeVariable>().apply {
                    addIfNotNull(typeVariableForReturnType)
                    addAll(candidate?.freshSubstitutor?.freshVariables.orEmpty())
                }
                is ResolvedCallableReferenceAtom -> candidate?.freshSubstitutor?.freshVariables.orEmpty()
                is ResolvedLambdaAtom -> listOfNotNull(typeVariableForLambdaReturnType)
                else -> emptyList()
            }
            typeVariables.mapNotNullTo(to) {
                val typeConstructor = it.freshTypeConstructor
                typeConstructor.takeIf { c.notFixedTypeVariables.containsKey(typeConstructor) }
            }

            /*
             * Hack for completing error candidates in delegate resolve
             */
            if (this is StubResolvedAtom && typeVariable in c.notFixedTypeVariables) {
                to += typeVariable
            }

            if (analyzed) {
                subResolvedAtoms?.forEach { it.process(to) }
            }
        }

        // Note that it's important to use Set here, because several atoms can share the same type variable
        val result = linkedSetOf<TypeConstructor>()
        for (primitive in topLevelAtoms) {
            primitive.process(result)
        }

        assert(result.size == c.notFixedTypeVariables.size) {
            val notFoundTypeVariables = c.notFixedTypeVariables.keys.toMutableSet().apply { removeAll(result) }
            "Not all type variables found: $notFoundTypeVariables"
        }

        return result.toList()
    }


    private fun canWeAnalyzeIt(c: Context, argument: PostponedResolvedAtom): Boolean {
        if (argument.analyzed) return false

        return argument.inputTypes.all { c.containsOnlyFixedOrPostponedVariables(it) }
    }

    private fun fixVariable(
        c: Context,
        variableWithConstraints: VariableWithConstraints,
        topLevelAtoms: List<ResolvedAtom>
    ) {
        fixVariable(c, variableWithConstraints, TypeVariableDirectionCalculator.ResolveDirection.UNKNOWN, topLevelAtoms)
    }

    fun fixVariable(
        c: Context,
        variableWithConstraints: VariableWithConstraints,
        direction: TypeVariableDirectionCalculator.ResolveDirection,
        topLevelAtoms: List<ResolvedAtom>
    ) {
        val resultType = resultTypeResolver.findResultType(c, variableWithConstraints, direction)
        val resolvedAtom = findResolvedAtomBy(variableWithConstraints.typeVariable, topLevelAtoms) ?: topLevelAtoms.firstOrNull()
        c.fixVariable(variableWithConstraints.typeVariable, resultType, resolvedAtom)
    }

    private fun processVariableWhenNotEnoughInformation(
        c: Context,
        variableWithConstraints: VariableWithConstraints,
        topLevelAtoms: List<ResolvedAtom>
    ) {
        val typeVariable = variableWithConstraints.typeVariable

        val resolvedAtom = findResolvedAtomBy(typeVariable, topLevelAtoms) ?: topLevelAtoms.firstOrNull()
        if (resolvedAtom != null) {
            c.addError(NotEnoughInformationForTypeParameter(typeVariable, resolvedAtom))
        }

        val resultErrorType = if (typeVariable is TypeVariableFromCallableDescriptor)
            ErrorUtils.createUninferredParameterType(typeVariable.originalTypeParameter)
        else
            ErrorUtils.createErrorType("Cannot infer type variable $typeVariable")

        c.fixVariable(typeVariable, resultErrorType, resolvedAtom)
    }

    private fun findResolvedAtomBy(typeVariable: TypeVariableMarker, topLevelAtoms: List<ResolvedAtom>): ResolvedAtom? {
        fun ResolvedAtom.check(): ResolvedAtom? {
            val suitableCall = when (this) {
                is ResolvedCallAtom -> typeVariable in freshVariablesSubstitutor.freshVariables
                is ResolvedCallableReferenceAtom -> candidate?.freshSubstitutor?.freshVariables?.let { typeVariable in it } ?: false
                is ResolvedLambdaAtom -> typeVariable == typeVariableForLambdaReturnType
                else -> false
            }

            if (suitableCall) {
                return this
            }

            subResolvedAtoms?.forEach { subResolvedAtom ->
                subResolvedAtom.check()?.let { result -> return@check result }
            }

            return null
        }

        for (topLevelAtom in topLevelAtoms) {
            topLevelAtom.check()?.let { return it }
        }

        return null
    }

    companion object {
        fun getOrderedNotAnalyzedPostponedArguments(topLevelAtoms: List<ResolvedAtom>): List<PostponedResolvedAtom> {
            fun ResolvedAtom.process(to: MutableList<PostponedResolvedAtom>) {
                to.addIfNotNull(this.safeAs<PostponedResolvedAtom>()?.takeUnless { it.analyzed })

                if (analyzed) {
                    subResolvedAtoms?.forEach { it.process(to) }
                }
            }

            val notAnalyzedArguments = arrayListOf<PostponedResolvedAtom>()
            for (primitive in topLevelAtoms) {
                primitive.process(notAnalyzedArguments)
            }

            return notAnalyzedArguments
        }
    }
}