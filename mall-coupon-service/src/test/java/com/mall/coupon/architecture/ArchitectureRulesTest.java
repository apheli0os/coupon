package com.mall.coupon.architecture;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.kafka.annotation.KafkaListener;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.mall.coupon", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule noStandardStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule controllersMustNotDependOnMappers = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..mapper..");

    @ArchTest
    static final ArchRule kafkaListenersMustDefineGroupId = methods()
            .that().areAnnotatedWith(KafkaListener.class)
            .should(new ArchCondition<>("define non-blank groupId") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    KafkaListener listener = method.getAnnotationOfType(KafkaListener.class);
                    boolean valid = listener.groupId() != null && !listener.groupId().trim().isEmpty();
                    events.add(new SimpleConditionEvent(
                            method,
                            valid,
                            method.getFullName() + " must declare non-blank @KafkaListener.groupId"));
                }
            });
}
