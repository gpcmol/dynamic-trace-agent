package org.otel.agent.extension.instrument;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.otel.agent.gateway.EnrichmentGateway;

/**
 * The advice woven into selected methods. Its code is <em>copied into application classes</em>, so
 * it may only use the JDK and the gateway (enforced by {@code ArchitectureTest}); see {@code
 * docs/03-classloaders.md} §3 and §5.
 *
 * <p>One advice serves void and value methods: the dynamically typed return value is {@code null}
 * for void methods (spike S7). {@code suppress = Throwable.class} guarantees the application never
 * sees an exception from enrichment.
 */
public final class ExitAdvice {

  private ExitAdvice() {}

  /** For methods whose rules read at most {@code $arg3}: no argument array is created. */
  public static final class FixedArguments {
    private FixedArguments() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Site final int site,
        @Advice.This final Object self,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final Object returned,
        @Advice.Argument(value = 0, optional = true, typing = Assigner.Typing.DYNAMIC)
            final Object a0,
        @Advice.Argument(value = 1, optional = true, typing = Assigner.Typing.DYNAMIC)
            final Object a1,
        @Advice.Argument(value = 2, optional = true, typing = Assigner.Typing.DYNAMIC)
            final Object a2,
        @Advice.Argument(value = 3, optional = true, typing = Assigner.Typing.DYNAMIC)
            final Object a3) {
      EnrichmentGateway.onExit(site, self, returned, a0, a1, a2, a3);
    }
  }

  /** For methods whose rules read {@code $arg4} or higher. */
  public static final class AllArguments {
    private AllArguments() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Site final int site,
        @Advice.This final Object self,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final Object returned,
        @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) final Object[] arguments) {
      EnrichmentGateway.onExitAllArgs(site, self, returned, arguments);
    }
  }
}
