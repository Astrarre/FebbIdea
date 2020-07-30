import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.*
import com.intellij.psi.search.searches.SuperMethodsSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.util.Processor

// Most things are copied from MethodSuperSearcher.java
class FebbApiBaseclassRedirector :
    QueryExecutorBase<MethodSignatureBackedByPsiMethod, SuperMethodsSearch.SearchParameters>() {
    override fun processQuery(
        queryParameters: SuperMethodsSearch.SearchParameters,
        consumer: Processor<in MethodSignatureBackedByPsiMethod?>
    ) {
//        println("PROCESS!")
        val parentClass = queryParameters.psiClass
        val method = queryParameters.method
        val signature = method.hierarchicalMethodSignature
        val checkBases = queryParameters.isCheckBases
        val allowStaticMethod = queryParameters.isAllowStaticMethod
        val supers = signature.superSignatures
        for (superSignature in supers) {
            if (superSignature.method.isApiMethod()) {
                if (MethodSignatureUtil.isSubsignature(superSignature, signature)) {
                    if (!addSuperMethods(
                            superSignature,
                            method,
                            parentClass,
                            allowStaticMethod,
                            checkBases,
                            consumer
                        )
                    ) return
                }
            }
        }
    }

    private fun addSuperMethods(
        signature: HierarchicalMethodSignature,
        method: PsiMethod,
        parentClass: PsiClass?,
        allowStaticMethod: Boolean,
        checkBases: Boolean,
        consumer: Processor<in MethodSignatureBackedByPsiMethod?>
    ): Boolean {
        val signatureMethod = signature.method
        val hisClass = signatureMethod.containingClass!!
        if (parentClass == null || InheritanceUtil.isInheritorOrSelf(parentClass, hisClass, true)) {
            if (isAcceptable(signatureMethod, method, allowStaticMethod)) {
                if (parentClass != null && parentClass != hisClass && !checkBases) {
                    return true
                }
//                MethodSuperSearcher.LOG.assertTrue(signatureMethod !== method, method) // method != method.getsuper()
                val equivalent = signature.method.getMcEquivalent() as? PsiMethod ?: return false
                return consumer.process(
                    HierarchicalMethodSignature.create(
                        equivalent,
                        PsiSubstitutor.EMPTY
                    )
                ) //no need to check super classes
            }
        }
        for (superSignature in signature.superSignatures) {
            if (MethodSignatureUtil.isSubsignature(superSignature, signature)) {
                addSuperMethods(superSignature, method, parentClass, allowStaticMethod, checkBases, consumer)
            }
        }
        return true
    }

    private fun isAcceptable(superMethod: PsiMethod, method: PsiMethod, allowStaticMethod: Boolean): Boolean {
        val hisStatic = superMethod.hasModifierProperty(PsiModifier.STATIC)
        return hisStatic == method.hasModifierProperty(PsiModifier.STATIC) &&
                (allowStaticMethod || !hisStatic) &&
                JavaPsiFacade.getInstance(method.project).resolveHelper.isAccessible(superMethod, method, null)
    }
}