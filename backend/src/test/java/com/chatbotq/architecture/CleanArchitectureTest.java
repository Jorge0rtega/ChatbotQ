package com.chatbotq.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
    packages = "com.chatbotq",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class CleanArchitectureTest {

    @ArchTest
    static final ArchRule applicationMustNotDependOnFrameworksOrAdapters = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..infrastructure..",
            "..web..",
            "org.springframework..",
            "javax.servlet.."
        );

    @ArchTest
    static final ArchRule applicationPortsMustBeTopLevelInterfaces = classes()
        .that().resideInAPackage("..application.port..")
        .and().areTopLevelClasses()
        .should().beInterfaces();

    @ArchTest
    static final ArchRule infrastructureMustNotLeakOutsideItsModule = noClasses()
        .that().resideOutsideOfPackage("..rag.infrastructure..")
        .should().dependOnClassesThat().resideInAPackage("..rag.infrastructure..");
}
