package org.otel.agent.extension.instrument;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;

/** Binds {@link Site} to an {@code int} constant: the {@link SiteRegistry} id of the method. */
final class SiteMappingFactory implements Advice.OffsetMapping.Factory<Site> {

  @Override
  public Class<Site> getAnnotationType() {
    return Site.class;
  }

  @Override
  public Advice.OffsetMapping make(
      final ParameterDescription.InDefinedShape target,
      final AnnotationDescription.Loadable<Site> annotation,
      final AdviceType adviceType) {
    return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) ->
        new Advice.OffsetMapping.Target.ForStackManipulation(
            IntegerConstant.forValue(
                SiteRegistry.siteOf(
                    new SiteRegistry.SiteInfo(
                        instrumentedType.getName(),
                        instrumentedMethod.getInternalName(),
                        instrumentedMethod.getDescriptor()))));
  }
}
