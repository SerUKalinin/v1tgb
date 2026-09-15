package com.tradingbot.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.tracing.ExecutionContext;

import java.util.List;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.tradingbot",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class IdentityArchitectureTest {

    /**
     * RULE:
     *
     * UUID.randomUUID() разрешен только внутри tracing.
     *
     * Все остальные identity должны создаваться детерминированно.
     */
    @ArchTest
    static final ArchRule identity_must_be_deterministic =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.tradingbot.tracing..")
                    .should()
                    .callMethod(UUID.class, "randomUUID")
                    .because(
                            "All identities must be deterministic and derived via IdentityFactory"
                    );

    /**
     * RULE:
     *
     * Canonical lifecycle identity:
     * - signalId
     * - orderId
     * - executionId
     *
     * должна быть immutable.
     *
     * Lombok Builder-классы не являются SSOT identity.
     */
    @ArchTest
    static final ArchRule identity_fields_must_be_immutable =
            fields()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAnyPackage(
                            "com.tradingbot.domain..",
                            "com.tradingbot.tracing.."
                    )
                    .and()
                    .haveNameMatching(
                            "signalId|orderId|executionId"
                    )
                    .should(
                            new ArchCondition<JavaField>(
                                    "be final for canonical identity fields"
                            ) {
                                @Override
                                public void check(
                                        JavaField field,
                                        ConditionEvents events
                                ) {
                                    JavaClass owner = field.getOwner();

                                    /*
                                     * Lombok-generated Builder fields are mutable
                                     * by design and are not domain SSOT.
                                     */
                                    if (owner.getSimpleName().endsWith("Builder")) {
                                        return;
                                    }

                                    if (!field.getModifiers().contains(
                                            com.tngtech.archunit.core.domain.JavaModifier.FINAL
                                    )) {
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        field,
                                                        "Canonical identity field " +
                                                                owner.getName() +
                                                                "." +
                                                                field.getName() +
                                                                " must be final"
                                                )
                                        );
                                    }
                                }
                            }
                    )
                    .because(
                            "Canonical lifecycle identity fields must be immutable SSOT"
                    );

    /**
     * RULE:
     *
     * Публичные execution/risk методы должны получать
     * ExecutionContext либо непосредственно, либо через SignalEvent,
     * который уже содержит ExecutionContext.
     */
    @ArchTest
    static final ArchRule services_must_use_execution_context =
            methods()
                    .that()
                    .arePublic()
                    .and()
                    .areDeclaredInClassesThat()
                    .resideInAnyPackage(
                            "..application.service..",
                            "..domain.risk.."
                    )
                    .and()
                    .haveNameMatching(
                            "reserve|release|createOrder|execute|commit.*"
                    )
                    .should(
                            new ArchCondition<JavaMethod>(
                                    "carry ExecutionContext"
                            ) {
                                @Override
                                public void check(
                                        JavaMethod method,
                                        ConditionEvents events
                                ) {
                                    List<JavaClass> parameters =
                                            method.getRawParameterTypes();

                                    /*
                                     * Normal case:
                                     * ExecutionContext is an explicit parameter.
                                     */
                                    boolean hasExecutionContext =
                                            parameters.stream()
                                                    .anyMatch(
                                                            parameterType ->
                                                                    parameterType.isEquivalentTo(
                                                                            ExecutionContext.class
                                                                    )
                                                    );

                                    if (hasExecutionContext) {
                                        return;
                                    }

                                    /*
                                     * SignalExecutionFacade.execute(SignalEvent)
                                     * is a valid boundary exception because
                                     * SignalEvent itself is the carrier of the
                                     * already-created ExecutionContext.
                                     */
                                    boolean isSignalExecutionFacade =
                                            method.getOwner()
                                                    .isEquivalentTo(
                                                            com.tradingbot.application.service.execution.SignalExecutionFacade.class
                                                    );

                                    boolean acceptsSignalEvent =
                                            parameters.size() == 1
                                                    && parameters.get(0)
                                                    .isEquivalentTo(
                                                            SignalEvent.class
                                                    );

                                    if (
                                            isSignalExecutionFacade
                                                    && acceptsSignalEvent
                                                    && method.getName().equals("execute")
                                    ) {
                                        return;
                                    }

                                    events.add(
                                            SimpleConditionEvent.violated(
                                                    method,
                                                    "Method " +
                                                            method.getFullName() +
                                                            " must carry ExecutionContext"
                                            )
                                    );
                                }
                            }
                    )
                    .because(
                            "ExecutionContext is the canonical identity carrier; SignalEvent is an approved boundary carrier"
                    );

    /**
     * RULE:
     *
     * Domain model и persistence не должны самостоятельно
     * пересобирать IdentityContext.
     */
    @ArchTest
    static final ArchRule identity_creation_restriction =
            noClasses()
                    .that()
                    .resideInAnyPackage(
                            "..domain.model..",
                            "..infrastructure.persistence.."
                    )
                    .should()
                    .callConstructor(
                            com.tradingbot.tracing.IdentityContext.class,
                            new Class<?>[]{
                                    UUID.class,
                                    UUID.class
                            }
                    )
                    .because(
                            "Identity must be passed through, never recreated in domain or persistence layers"
                    );
}