package github.cweijan.http.test.core;

import com.intellij.ide.fileTemplates.FileTemplateUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.testIntegration.createTest.CreateTestAction;
import com.intellij.testIntegration.createTest.JavaTestGenerator;
import github.cweijan.http.test.template.java.JavaTestTemplate;
import github.cweijan.http.test.util.PsiClassUtils;
import github.cweijan.http.test.util.ReflectUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * @author cweijan
 * @since 2020/10/30 23:35
 */
public class Generator {

    public static final String ANNOTATION_NAME = "io.github.cweijan.mock.jupiter.HttpTest";

    public static PsiClass getOrCreateTestClass(GenerateContext generateContext) {

        @NotNull Project project = generateContext.project;
        @NotNull PsiClass originClass = generateContext.sourceClass;
        String testClassName = originClass.getName() + "Test";

        final PsiPackage srcPackage = JavaDirectoryService.getInstance().getPackage(originClass.getContainingFile().getContainingDirectory());
        PsiDirectory psiDirectory = getTestDirectory(project, originClass, srcPackage);

        PsiClass existsTestClass = findExistsTestClass(testClassName, psiDirectory);
        if (existsTestClass != null) {
            return existsTestClass;
        }

        Properties properties = new Properties();
        properties.setProperty("NAME", testClassName);
        properties.setProperty("CLASS_NAME", originClass.getQualifiedName());
        PsiClass testClass = ReflectUtil.invoke(FileTemplateUtil.class, "createFromTemplate",
                JavaTestTemplate.instance.loadTestClassTemplate(), testClassName, properties, psiDirectory);
        generateContext.testClass = testClass;

        PsiClassUtils.doWrite(project, () -> {

            if (generateContext.superClassName != null && !generateContext.superClassName.equals("")) {
                ReflectUtil.invoke(JavaTestGenerator.class, "addSuperClass", testClass, project, generateContext.superClassName);
            }

            if (generateContext.createBefore) {
                createBeforeMethod(generateContext);
            }

            checkAndAddAnnotation(project, testClass);

            return testClass;
        });

        return testClass;

    }

    private static void createBeforeMethod(GenerateContext generateContext) {
        String fullMethod = "/**\n" +
                " * 拦截请求, 可在请求之前做一些操作\n" +
                " */\n" +
                "@org.junit.jupiter.api.BeforeAll\n" +
                "public static void beforeRequest(){\n" +
                "    addRequestInterceptor(request ->{\n" +
                "        request.header(\"token\",\"c2f678d4873c472c8f99940e8cf39fe4\");\n" +
                "    });\n" +
                "}";
        PsiMethod beforeMethod = JVMElementFactories.getFactory(generateContext.testClass.getLanguage(), generateContext.project).createMethodFromText(fullMethod, generateContext.testClass);
        generateContext.testClass.add(beforeMethod);
        JavaCodeStyleManager.getInstance(generateContext.project).shortenClassReferences(generateContext.testClass);
    }

    private static void checkAndAddAnnotation(Project project, PsiClass testClass) {
        PsiClass superClass = testClass.getSuperClass();
        List<PsiAnnotation> allAnnotations = Arrays.asList(testClass.getAnnotations());
        while (superClass != null) {
            allAnnotations.addAll(Arrays.asList(superClass.getAnnotations()));
            superClass = superClass.getSuperClass();
        }

        for (PsiAnnotation annotation : allAnnotations) {
            if (annotation.getQualifiedName().equals(ANNOTATION_NAME)) {
                return;
            }
        }

        PsiModifierList classModifierList = testClass.getModifierList();
        PsiAnnotation addAnnotation = classModifierList.addAnnotation(ANNOTATION_NAME);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(addAnnotation);

    }

    @Nullable
    private static PsiDirectory getTestDirectory(Project project, @NotNull PsiClass psiClass, PsiPackage srcPackage) {
        final Module srcModule = ModuleUtilCore.findModuleForPsiElement(psiClass);
        Module testModule = CreateTestAction.suggestModuleForTests(project, srcModule);
        PackageCreator packageCreator = new PackageCreator(project, testModule);
        return packageCreator.createPackage(srcPackage.getQualifiedName());
    }

    @Nullable
    private static PsiClass findExistsTestClass(String testClassName, PsiDirectory psiDirectory) {
        GlobalSearchScope scope = GlobalSearchScopesCore.directoryScope(psiDirectory, false);
        PsiPackage aPackage = JavaDirectoryService.getInstance().getPackage(psiDirectory);
        PsiClass[] classes = aPackage.findClassByShortName(testClassName, scope);
        if (classes.length > 0) {
            return classes[0];
        }
        return null;
    }

    public static void importClass(PsiJavaFile psiJavaFile, PsiMethod psiMethod) {

        PsiClass psiClass = PsiTypesUtil.getPsiClass(psiMethod.getReturnType());
        if (psiClass != null) {
            psiJavaFile.importClass(psiClass);
        }

        for (PsiParameter parameter : psiMethod.getParameterList().getParameters()) {
            PsiClass parameterClass = PsiTypesUtil.getPsiClass(parameter.getType());
            if (parameterClass != null) {
                psiJavaFile.importClass(parameterClass);
            }
        }

    }

    public static FieldCode generateSetter(PsiParameter parameter) {

        PsiClass parameterClass = PsiTypesUtil.getPsiClass(parameter.getType());
        FieldCode fieldCode = new FieldCode(parameterClass);

        StringBuilder stringBuilder = new StringBuilder(fieldCode.getNewStatement() + ";\n");
        for (PsiMethod setMethod : PsiClassUtils.extractSetMethods(parameterClass)) {
            stringBuilder.append("    ").append(
                    String.format("%s.%s(request(%s.class))", fieldCode.getName(), setMethod.getName(),
                            ((PsiClassReferenceType) setMethod.getParameterList().getParameters()[0].getType()).getClassName())
            ).append(";\n");
        }
        fieldCode.setSetCode(stringBuilder.toString());

        return fieldCode;
    }

    public static PsiMethod generateMethodContent(Project project, PsiClass sourceClass, PsiClass testClass, PsiMethod method) {

        PsiField sourceField = findField(testClass, sourceClass);

        ArrayList<String> params = new ArrayList<>();
        StringBuilder methodContent = new StringBuilder();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            FieldCode fieldCode = generateSetter(parameter);
            methodContent.append(fieldCode.getSetCode());
            params.add(fieldCode.getName());
        }
        String returnTypeStr = method.getReturnTypeElement().getText();
        String methodName = method.getName();
        methodContent.append(
                String.format("%s %s=%s.%s(%s);", returnTypeStr, methodName, sourceField.getName(), methodName, String.join(",", params))
        );
        String fullMethod = "@org.junit.jupiter.api.Test\n" +
                "void " + methodName + "(){\n" +
                "    " + methodContent.toString() + "\n" +
                "}\n" +
                "\n";

        return JVMElementFactories.getFactory(testClass.getLanguage(), project).createMethodFromText(fullMethod, testClass);
    }

    private static PsiField findField(PsiClass testClass, PsiClass sourceClass) {
        for (PsiField testClassField : testClass.getAllFields()) {
            if (testClassField.getType().getCanonicalText().equals(sourceClass.getQualifiedName())) {
                return testClassField;
            }

        }

        // TODO 如果没有找到则创建新的Field
        throw new UnsupportedOperationException("");
    }


}
