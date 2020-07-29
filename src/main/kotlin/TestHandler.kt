@file:Suppress("LiftReturnOrAssignment", "UnnecessaryVariable")

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.search.GlobalSearchScope

fun PsiElement.getDefinition(): PsiElement? = containingFile.findReferenceAt(textOffset)?.resolve()

private fun String.splitToPackageAndClassName(): Pair<String, String> {
    val split = split(".")
    val packageName = split.filter { !it[0].isUpperCase() }.joinToString(".")
    val className = split.filter { it[0].isUpperCase() }.joinToString(".")
    return packageName to className
}

private fun String.isApiClassName(): Boolean {
    val (packageName, className) = splitToPackageAndClassName()
    return packageName.startsWith("v") && packageName.substringAfter(".").startsWith("net.minecraft")
            && className.startsWith("I") || className.startsWith("Base")
}

private fun String.removeApiPrefix() = if (startsWith("I")) removePrefix("I") else removePrefix("Base")

private fun String.apiToMcClassName(): String {
    val (packageName, className) = splitToPackageAndClassName()
    return packageName.substringAfter(".") + "." + className.removeApiPrefix()
}

private fun String.toMcClassName(): String = if (isApiClassName()) apiToMcClassName() else this

private fun PsiMethod.isMatchingMethod(apiMethod: PsiMethod): Boolean {
    if (this.parameterList.parametersCount != apiMethod.parameterList.parametersCount) return false
    this.parameterList.parameters.zip(apiMethod.parameterList.parameters).forEach { (mcParam, apiParam) ->
        if (!mcParam.type.matchesApiType(apiParam.type)) return false
    }

    return true
}

private fun PsiClassReferenceType.rawTypeString() = rawType().canonicalText
private fun PsiType.matchesApiType(apiType: PsiType): Boolean {
    val mcType = this
    if (mcType is PsiClassReferenceType) {
        if (apiType !is PsiClassReferenceType) return false
        if (mcType.rawTypeString() != apiType.rawTypeString().toMcClassName()) return false
        return true
    } else {
        return false
    }
}

private fun String.removeGetterSetterPrefix() = if (startsWith("get")) removePrefix("get")
else removePrefix("set")

private fun String.decapitalizeFirstLetter() = this[0].toLowerCase() + substring(1)

//TODO: technically  getters/setters get a _field suffix when conflicting,
// and methods get _method when conflicting with a create() factory.

private fun PsiMethod.getMcEquivalent(): PsiElement? {
    val classIn = containingClass ?: return null
    val className = classIn.qualifiedName ?: return null
    if (className.isApiClassName()) {
        val mcClassName = className.apiToMcClassName()
        val mcClass = JavaPsiFacade.getInstance(project)
            .findClass(mcClassName, GlobalSearchScope.everythingScope(project))
            ?: error("Could not find mc class '$mcClassName' corresponding to api class '$className'")
        val methodName = if (name == "create") classIn.name?.removeApiPrefix() ?: return null else name
        val methods = mcClass.findMethodsByName(methodName, false)

        if (methods.isNotEmpty()) {
            return getMcMethod(methods, mcClassName)
        } else {
            return getMcField(mcClass)
        }
    } else {
        return null
    }
}

private fun PsiMethod.getMcField(mcClass: PsiClass): PsiField? {
    if (name.startsWith("get") || name.startsWith("set")) {
        val fieldName = name.removeGetterSetterPrefix()
        val field = mcClass.findFieldByName(fieldName, false)
            ?: mcClass.findFieldByName(fieldName.decapitalizeFirstLetter(), true)
            ?: error("Could not find any fields named '$fieldName' in mc class '$mcClass' (capitalized or not)")
        return field
    } else {
        return null
//        error("Could not find any mc methods named '$name' in mc class '$mcClass'")
    }
}

private fun PsiMethod.getMcMethod(
    methods: Array<out PsiMethod>,
    mcClassName: String
): PsiMethod {
    val correctMethod = methods.filter { it.isMatchingMethod(this) }
    if (correctMethod.isEmpty()) {
        error("Could not find matching mc method for api method '$name' in mc class '$mcClassName'")
    }
    if (correctMethod.size > 1) {
        error("Found too many matching mc methods for api method '$name' in mc class '$mcClassName':" +
                correctMethod.joinToString { "'${it.name}'" })
    }
    return correctMethod.single()
}

class TestHandler : GotoDeclarationHandlerBase() {
    override fun getGotoDeclarationTarget(sourceElement: PsiElement?, editor: Editor): PsiElement? {
        if (sourceElement is PsiIdentifier) {
            val definition = sourceElement.getDefinition() ?: return null
            when (definition) {
                is PsiMethod -> return definition.getMcEquivalent()
            }
        }
        return null
    }

}