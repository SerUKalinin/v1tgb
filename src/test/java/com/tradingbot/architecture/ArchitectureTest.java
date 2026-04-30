//package com.tradingbot.architecture;
//
//import com.tngtech.archunit.core.importer.ImportOption;
//import com.tngtech.archunit.junit.AnalyzeClasses;
//import com.tngtech.archunit.junit.ArchTest;
//import com.tngtech.archunit.lang.ArchRule;
//
//import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
//
//@AnalyzeClasses(packages = "com.tradingbot", importOptions = ImportOption.DoNotIncludeTests.class)
//public class ArchitectureTest {
//
//    @ArchTest
//    static final ArchRule domain_should_not_depend_on_infrastructure =
//            noClasses().that().resideInAPackage("..domain..")
//                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..");
//
//    @ArchTest
//    static final ArchRule application_should_not_depend_on_persistence =
//            noClasses().that().resideInAPackage("..application..")
//                    .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence..");
//
//    @ArchTest
//    static final ArchRule execution_should_not_depend_on_risk_directly =
//            noClasses().that().resideInAPackage("..application.service.execution..")
//                    .should().dependOnClassesThat().resideInAPackage("..domain.risk..");
//}
