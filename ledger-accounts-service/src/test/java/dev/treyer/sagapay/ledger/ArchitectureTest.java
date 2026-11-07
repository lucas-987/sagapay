package dev.treyer.sagapay.ledger;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Hexagonal dependency rules. JPA mapping annotations stay allowed in the domain. */
@AnalyzeClasses(packages = "dev.treyer.sagapay.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNoOtherLayer = noClasses()
            .that()
            .resideInAPackage("..ledger.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..ledger.application..", "..ledger.adapter..", "..ledger.config..", "org.springframework..");

    @ArchTest
    static final ArchRule applicationDependsOnPortsNotAdapters = noClasses()
            .that()
            .resideInAPackage("..ledger.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..ledger.adapter..",
                    "..ledger.config..",
                    "org.springframework.data..",
                    "org.springframework.web..",
                    "io.grpc..",
                    "jakarta.servlet..");

    @ArchTest
    static final ArchRule inboundAdaptersDoNotCallOutboundAdapters = noClasses()
            .that()
            .resideInAPackage("..ledger.adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..ledger.adapter.out..");

    // The local seed creates accounts, which no use case offers.
    @ArchTest
    static final ArchRule inboundAdaptersGoThroughUseCases = noClasses()
            .that()
            .resideInAPackage("..ledger.adapter.in..")
            .and()
            .doNotHaveSimpleName("LocalAccountSeeder")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..ledger.application.port.out..");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotReachTheInboundSide = noClasses()
            .that()
            .resideInAPackage("..ledger.adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..ledger.adapter.in..", "..ledger.application.port.in..");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotDependOnEachOther =
            slices().matching("..ledger.adapter.out.(*)..").should().notDependOnEachOther();
}
