package com.tradingbot.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.tradingbot.domain",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class DomainArchitectureTest {

    /**
     * DOMAIN MUST BE PURE
     * (no dependency on outer layers)
     */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_outer_layers =
            noClasses()
                    .that()
                    .resideInAPackage("com.tradingbot.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.tradingbot.infrastructure..",
                            "com.tradingbot.application..",
                            "org.springframework..",
                            "jakarta.persistence..",
                            "javax.persistence.."
                    );

    /**
     * DOMAIN MUST NOT KNOW PERSISTENCE FRAMEWORKS
     */
    @ArchTest
    static final ArchRule domain_must_not_use_persistence_frameworks =
            noClasses()
                    .that()
                    .resideInAPackage("com.tradingbot.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "jakarta.persistence..",
                            "javax.persistence.."
                    );
}