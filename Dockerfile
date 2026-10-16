# Extension image for the OpenTelemetry Operator (Instrumentation CR → spec.java.extensions).
# Contains only /extensions/dynamic-trace-agent.jar on a minimal base that provides `cp`, which the
# Operator's copy init container needs. See docs/02-architecture.md §2.
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -q -DskipTests -pl dta-extension -am package \
    && mkdir -p /out/extensions \
    && cp dta-extension/target/dynamic-trace-agent.jar /out/extensions/dynamic-trace-agent.jar

FROM busybox:1.38.0
COPY --from=build /out/extensions/ /extensions/
RUN chmod go+r /extensions/*.jar
