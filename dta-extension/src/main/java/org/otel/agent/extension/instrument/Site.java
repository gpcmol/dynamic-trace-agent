package org.otel.agent.extension.instrument;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the advice parameter that receives the method's constant site id. Resolved by {@link
 * SiteMappingFactory} when the advice is woven; not present in the woven code.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Site {}
