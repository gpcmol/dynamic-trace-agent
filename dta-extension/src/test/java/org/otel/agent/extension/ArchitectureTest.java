package org.otel.agent.extension;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules for all production code of the agent (core, gateway and extension).
 *
 * <p>These rules encode decisions from {@code docs/02-architecture.md} and {@code
 * docs/03-classloaders.md}. If a rule fails, read the linked document before weakening it.
 */
class ArchitectureTest {

  private static final String[] JDK_PACKAGES = {
    "java..", "javax.xml..", "org.w3c.dom..", "org.xml.sax.."
  };

  private static JavaClasses productionClasses;

  @BeforeAll
  static void importClasses() {
    productionClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.otel.agent");
  }

  @Test
  void packagesAreFreeOfCycles() {
    slices()
        .matching("org.otel.agent.(**)")
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true)
        .check(productionClasses);
  }

  @Test
  void coreDependsOnTheJdkOnly() {
    classes()
        .that()
        .resideInAPackage("org.otel.agent.core..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(withJdk("org.otel.agent.core.."))
        .allowEmptyShould(true)
        .check(productionClasses);
  }

  @Test
  void gatewayDependsOnTheJdkOnly() {
    classes()
        .that()
        .resideInAPackage("org.otel.agent.gateway..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", "org.otel.agent.gateway..")
        .allowEmptyShould(true)
        .check(productionClasses);
  }

  /**
   * Advice code is copied into application classes, so the code in its method bodies may only use
   * the JDK and the gateway. Annotations on advice parameters are not copied and are ignored.
   */
  @Test
  void adviceCodeUsesOnlyJdkAndGateway() {
    classes()
        .that(declareAdviceMethods())
        .should(useInMethodBodiesOnly("java.", "org.otel.agent.gateway."))
        .allowEmptyShould(true)
        .check(productionClasses);
  }

  /**
   * Configuration, plans, snapshots and parsed expressions are held in long-lived fields; they must
   * never reference application classes ({@code docs/03-classloaders.md} §7).
   */
  @Test
  void snapshotDataHoldsNoClassReferences() {
    fields()
        .that()
        .areDeclaredInClassesThat()
        .resideInAnyPackage(
            "org.otel.agent.core.config..",
            "org.otel.agent.core.plan..",
            "org.otel.agent.core.snapshot..")
        .or()
        .areDeclaredInClassesThat()
        .areAssignableTo("org.otel.agent.core.dsl.Expression")
        .or()
        .areDeclaredInClassesThat()
        .areAssignableTo("org.otel.agent.core.dsl.Operand")
        .should()
        .notHaveRawType(Class.class)
        .andShould()
        .notHaveRawType(ClassLoader.class)
        .check(productionClasses);
  }

  /** A {@code Map} keyed by {@code Class} in agent code pins application classloaders. */
  @Test
  void noMapsKeyedByClass() {
    fields().should(notBeAMapKeyedByClass()).allowEmptyShould(true).check(productionClasses);
  }

  private static String[] withJdk(final String ownPackage) {
    final String[] packages = new String[JDK_PACKAGES.length + 1];
    System.arraycopy(JDK_PACKAGES, 0, packages, 0, JDK_PACKAGES.length);
    packages[JDK_PACKAGES.length] = ownPackage;
    return packages;
  }

  private static DescribedPredicate<JavaClass> declareAdviceMethods() {
    return DescribedPredicate.describe(
        "declare Byte Buddy advice methods",
        javaClass -> {
          for (final JavaMethod method : javaClass.getMethods()) {
            if (method.isAnnotatedWith("net.bytebuddy.asm.Advice$OnMethodExit")
                || method.isAnnotatedWith("net.bytebuddy.asm.Advice$OnMethodEnter")) {
              return true;
            }
          }
          return false;
        });
  }

  private static ArchCondition<JavaClass> useInMethodBodiesOnly(final String... allowedPrefixes) {
    return new ArchCondition<>("use in method bodies only " + String.join(", ", allowedPrefixes)) {
      @Override
      public void check(final JavaClass javaClass, final ConditionEvents events) {
        for (final JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
          final String owner = access.getTargetOwner().getName();
          if (!startsWithAny(owner, allowedPrefixes)) {
            events.add(SimpleConditionEvent.violated(access, access.getDescription()));
          }
        }
      }
    };
  }

  private static boolean startsWithAny(final String name, final String... prefixes) {
    for (final String prefix : prefixes) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static ArchCondition<JavaField> notBeAMapKeyedByClass() {
    return new ArchCondition<>("not be a Map keyed by Class") {
      @Override
      public void check(final JavaField field, final ConditionEvents events) {
        final Type type = field.reflect().getGenericType();
        if (type instanceof ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> raw
            && Map.class.isAssignableFrom(raw)
            && isClassType(parameterized.getActualTypeArguments()[0])) {
          events.add(
              SimpleConditionEvent.violated(field, field.getFullName() + " is keyed by Class"));
        }
      }
    };
  }

  private static boolean isClassType(final Type type) {
    return type == Class.class
        || (type instanceof ParameterizedType parameterized
            && parameterized.getRawType() == Class.class);
  }
}
