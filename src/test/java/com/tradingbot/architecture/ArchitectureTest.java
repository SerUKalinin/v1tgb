//package com.tradingbot.architecture;
//
//import com.tngtech.archunit.core.importer.ImportOption;
//import com.tngtech.archunit.junit.AnalyzeClasses;
//import com.tngtech.archunit.junit.ArchTest;
//import com.tngtech.archunit.lang.ArchRule;
//
//import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
//
//@AnalyzeClasses(
//        packages = "com.tradingbot",
//        importOptions = ImportOption.DoNotIncludeTests.class
//)
//public class ArchitectureTest {
//
//    /**
//     * DOMAIN — полностью изолирован
//     */
//    @ArchTest
//    static final ArchRule domain_must_be_isolated =
//            noClasses().that().resideInAPackage("..domain..")
//                    .should().dependOnClassesThat()
//                    .resideInAnyPackage(
//                            "..application..",
//                            "..infrastructure..",
//                            "..springframework.."
//                    );
//
//    /**
//     * APPLICATION — не должен знать persistence слой напрямую
//     */
//    @ArchTest
//    static final ArchRule application_must_not_depend_on_persistence =
//            noClasses().that().resideInAPackage("..application..")
//                    .should().dependOnClassesThat()
//                    .resideInAnyPackage(
//                            "..infrastructure.persistence.."
//                    );
//
//    /**
//     * APPLICATION — не должен напрямую зависеть от entity
//     */
//    @ArchTest
//    static final ArchRule application_must_not_use_entities =
//            noClasses().that().resideInAPackage("..application..")
//                    .should().dependOnClassesThat()
//                    .resideInAPackage("..infrastructure.persistence.entity..");
//
//    /**
//     * DOMAIN — не должен знать инфраструктуру риска/исполнения/персистенции
//     */
//    @ArchTest
//    static final ArchRule domain_must_not_depend_on_infrastructure =
//            noClasses().that().resideInAPackage("..domain..")
//                    .should().dependOnClassesThat()
//                    .resideInAPackage("..infrastructure..");
//
//    /**
//     * EXECUTION — изоляция bounded context (мягкое правило)
//     *  НЕ запрещаем risk полностью, только domain coupling через implementation
//     */
//    @ArchTest
//    static final ArchRule execution_should_not_directly_depend_on_risk_impl =
//            noClasses().that().resideInAPackage("..application.service.execution..")
//                    .should().dependOnClassesThat()
//                    .resideInAPackage("..domain.risk.impl..");
//}