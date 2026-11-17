package dev.treyer.sagapay.ledger;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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

    /** The dependency rules let the domain hold a class that only an adapter uses, such as an
     * exception of a request format: nothing business about it, so it belongs to that adapter. */
    @ArchTest
    static final ArchRule domainClassesServeTheApplicationOrTheDomain = classes()
            .that()
            .resideInAPackage("..ledger.domain..")
            .and()
            .areTopLevelClasses()
            .should(beUsedFrom("..ledger.domain..", "..ledger.application.."));

    private static ArchCondition<JavaClass> beUsedFrom(String... packages) {
        return new ArchCondition<>("be used by a class of " + String.join(" or ", packages)) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean used = item.getDirectDependenciesToSelf().stream()
                        .map(Dependency::getOriginClass)
                        .filter(origin -> !origin.getName().equals(item.getName())
                                && !origin.getName().startsWith(item.getName() + "$"))
                        .anyMatch(resideInAnyPackage(packages));
                String verb = used ? " is used by " : " is not used by ";
                events.add(new SimpleConditionEvent(item, used, item.getName() + verb + String.join(" or ", packages)));
            }
        };
    }
}
