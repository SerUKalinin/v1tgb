package com.tradingbot.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tradingbot.tracing.ExecutionContext;

import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.tradingbot",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class IdentityArchitectureTest {

    /**
     * RULE: UUID.randomUUID() разрешен ТОЛЬКО внутри IdentityFactory.
     * Все остальные ID должны быть детерминированными.
     */
    @ArchTest
    static final ArchRule identity_must_be_deterministic =
            noClasses().that().resideOutsideOfPackage("com.tradingbot.tracing..")
                    .should().callMethod(UUID.class, "randomUUID")
                    .because("All identities must be deterministic and derived via IdentityFactory.newRoot() or derive()");

    /**
     * RULE: Поля идентичности должны быть финальными (Immutable).
     * Запрещает любые setters для identity.
     */
    @ArchTest
    static final ArchRule identity_fields_must_be_immutable =
            fields().that().haveNameMatching(".*Id")
                    .and().areDeclaredInClassesThat().resideInAnyPackage("com.tradingbot.domain..", "com.tradingbot.tracing..")
                    .should().beFinal()
                    .because("Identity fields (signalId, orderId, executionId) must be immutable SSOT");

    /**
     * RULE: Публичные методы сервисов должны принимать ExecutionContext.
     * Запрещает передачу UUID в обход контекста.
     */
    @ArchTest
    static final ArchRule services_must_use_execution_context =
            methods().that().arePublic()
                    .and().areDeclaredInClassesThat().resideInAnyPackage("..application.service..", "..domain.risk..")
                    .and().haveNameMatching("reserve|release|createOrder|execute|commit.*")
                    .should().haveRawParameterTypes(ExecutionContext.class)
                    .because("ExecutionContext is the only allowed carrier for identity propagation");

    /**
     * RULE: Запрет на пересборку identity в разных слоях.
     * IdentityContext и ExecutionAttemptContext должны создаваться только в tracing или при входе.
     */
    @ArchTest
    static final ArchRule identity_creation_restriction =
            noClasses().that().resideInAnyPackage("..domain.model..", "..infrastructure.persistence..")
                    .should().callConstructor(com.tradingbot.tracing.IdentityContext.class, new Class[]{UUID.class, UUID.class})
                    .because("Identity must be passed through, never recreated in domain or persistence layers");
}
