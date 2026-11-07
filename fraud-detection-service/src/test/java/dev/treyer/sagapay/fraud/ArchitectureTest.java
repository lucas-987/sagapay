package dev.treyer.sagapay.fraud;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Hexagonal dependency rules. */
@AnalyzeClasses(packages = "dev.treyer.sagapay.fraud", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNoOtherLayer = noClasses()
            .that()
            .resideInAPackage("..fraud.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..fraud.application..", "..fraud.adapter..", "..fraud.config..", "org.springframework..");

    @ArchTest
    static final ArchRule applicationDependsOnPortsNotAdapters = noClasses()
            .that()
            .resideInAPackage("..fraud.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..fraud.adapter..",
                    "..fraud.config..",
                    "org.springframework.data..",
                    "org.springframework.web..",
                    "org.springframework.kafka..",
                    "jakarta.servlet..");

    @ArchTest
    static final ArchRule inboundAdaptersDoNotCallOutboundAdapters = noClasses()
            .that()
            .resideInAPackage("..fraud.adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..fraud.adapter.out..");

    @ArchTest
    static final ArchRule inboundAdaptersGoThroughUseCases = noClasses()
            .that()
            .resideInAPackage("..fraud.adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..fraud.application.port.out..");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotReachTheInboundSide = noClasses()
            .that()
            .resideInAPackage("..fraud.adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..fraud.adapter.in..", "..fraud.application.port.in..");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotDependOnEachOther =
            slices().matching("..fraud.adapter.out.(*)..").should().notDependOnEachOther();
}
