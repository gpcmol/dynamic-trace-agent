package org.otel.agent.extension.instrument;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Registers the enrichment advice with the OpenTelemetry agent. Which methods are instrumented is
 * decided by {@link PlanMatchers} from the {@link ActivePlan}; with an empty plan nothing is.
 */
@AutoService(InstrumentationModule.class)
public final class EnrichmentInstrumentationModule extends InstrumentationModule {

  public EnrichmentInstrumentationModule() {
    super("dynamic-trace-enrichment");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(new SelectedMethods());
  }

  private static final class SelectedMethods implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return PlanMatchers.types();
    }

    @Override
    public void transform(final TypeTransformer transformer) {
      transformer.applyAdviceToMethod(
          PlanMatchers.methods(false),
          mapping -> mapping.bind(new SiteMappingFactory()),
          ExitAdvice.FixedArguments.class.getName());
      transformer.applyAdviceToMethod(
          PlanMatchers.methods(true),
          mapping -> mapping.bind(new SiteMappingFactory()),
          ExitAdvice.AllArguments.class.getName());
    }
  }
}
