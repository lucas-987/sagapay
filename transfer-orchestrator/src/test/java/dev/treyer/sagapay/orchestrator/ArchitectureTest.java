package dev.treyer.sagapay.orchestrator;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Hexagonal dependency rules. JPA mapping annotations stay allowed in the domain. */
@AnalyzeClasses(packages = "dev.treyer.sagapay.orchestrator", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNoOtherLayer = noClasses()
            .that()
            .resideInAPackage("..orchestrator.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..orchestrator.application..",
                    "..orchestrator.adapter..",
                    "..orchestrator.config..",
                    "org.springframework..");

    @ArchTest
    static final ArchRule applicationDependsOnPortsNotAdapters = noClasses()
            .that()
            .resideInAPackage("..orchestrator.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..orchestrator.adapter..",
                    "..orchestrator.config..",
                    "org.springframework.data..",
                    "org.springframework.web..",
                    "io.grpc..",
                    "jakarta.servlet..");

    @ArchTest
    static final ArchRule inboundAdaptersDoNotCallOutboundAdapters = noClasses()
            .that()
            .resideInAPackage("..orchestrator.adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..orchestrator.adapter.out..");
}
