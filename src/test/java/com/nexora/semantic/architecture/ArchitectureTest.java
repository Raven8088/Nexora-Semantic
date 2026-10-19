package com.nexora.semantic.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Hexagonal architecture rules (docs/ARCHITECTURE.md §1-2). */
@AnalyzeClasses(packages = "com.nexora.semantic", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.nexora.semantic";

    @ArchTest
    static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy(ROOT + ".domain..")
            .layer("Application").definedBy(ROOT + ".application..")
            .layer("Infrastructure").definedBy(ROOT + ".infrastructure..")
            .layer("Api").definedBy(ROOT + ".api..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Api", "Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Api", "Infrastructure");

    @ArchTest
    static final ArchRule domainIsFrameworkFree = classes().that().resideInAPackage(ROOT + ".domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", ROOT + ".domain..")
            .because("the domain must not depend on Spring, Jakarta, Jackson or any other framework");

    @ArchTest
    static final ArchRule applicationOnlyUsesSpringTransactions = classes().that()
            .resideInAPackage(ROOT + ".application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", ROOT + ".domain..", ROOT + ".application..", "org.springframework.transaction.annotation..")
            .because("decision D7: @Transactional is the only Spring dependency allowed in application");

    @ArchTest
    static final ArchRule applicationHasNoSpringStereotypes = noClasses().that()
            .resideInAnyPackage(ROOT + ".domain..", ROOT + ".application..")
            .should().beAnnotatedWith(Service.class)
            .orShould().beAnnotatedWith(Component.class)
            .orShould().beAnnotatedWith(Repository.class);

    @ArchTest
    static final ArchRule jdbcOnlyInPersistence = noClasses().that()
            .resideOutsideOfPackages(ROOT + ".infrastructure.persistence..", ROOT + ".infrastructure.config..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
            .because("SQL access is confined to the persistence adapters (config only wires them)");

    @ArchTest
    static final ArchRule controllersLiveInApi = classes().that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(RestControllerAdvice.class)
            .should().resideInAPackage(ROOT + ".api..");

    @ArchTest
    static final ArchRule apiDoesNotUseInfrastructure = noClasses().that().resideInAPackage(ROOT + ".api..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure..");

    @ArchTest
    static final ArchRule noCyclesBetweenModules = slices().matching(ROOT + ".(*).(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule noCredentialFields = noFields()
            .that().areNotStatic().or().areNotFinal()
            .should().haveNameMatching("(?i).*(password|passwd|pwd|secret|credential).*")
            .because("Semantic never stores or transports connection credentials as state; only static final "
                    + "constants naming configuration variables (e.g. the migration job's environment) are allowed");
}
