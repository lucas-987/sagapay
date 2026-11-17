package dev.treyer.sagapay.orchestrator;

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

    @ArchTest
    static final ArchRule inboundAdaptersGoThroughUseCases = noClasses()
            .that()
            .resideInAPackage("..orchestrator.adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..orchestrator.application.port.out..");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotReachTheInboundSide = noClasses()
            .that()
            .resideInAPackage("..orchestrator.adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..orchestrator.adapter.in..", "..orchestrator.application.port.in..");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotDependOnEachOther =
            slices().matching("..orchestrator.adapter.out.(*)..").should().notDependOnEachOther();

    /** The dependency rules let the domain hold a class that only an adapter uses, such as an
     * exception of a request format: nothing business about it, so it belongs to that adapter. */
    @ArchTest
    static final ArchRule domainClassesServeTheApplicationOrTheDomain = classes()
            .that()
            .resideInAPackage("..orchestrator.domain..")
            .and()
            .areTopLevelClasses()
            .should(beUsedFrom("..orchestrator.domain..", "..orchestrator.application.."));

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
