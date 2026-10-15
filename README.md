# Dynamic Trace Agent

**Add business context to existing OpenTelemetry traces in production — without changing,
rebuilding, redeploying or restarting the application.**

An extension for the OpenTelemetry Java agent. You write a rule in an XML file (a ConfigMap in
Kubernetes); the running JVM picks it up and the selected methods add attributes to the spans that
already exist. Remove the rule when the incident is over and the instrumentation is gone again.

```
edit rule ──► agent reloads ──► matching calls enrich their spans ──► tail sampler keeps the trace
```

```xml
<configuration>
    <static>
        <attribute key="debug" value="true"/>
    </static>
    <dynamic>
        <rule>
            <selector><class name="com.example.Garage"/><method name="park"/></selector>
            <when>$arg0.brand in ['bmw', 'audi'] and $arg0.mileage gt 0</when>
            <attribute key="brand"      path="$arg0.brand"/>
            <attribute key="passenger"  path="$arg0.passengers[1].name"/>
            <attribute key="status"     path="$return.status"/>
        </rule>
    </dynamic>
</configuration>
```

Reads as: *when `Garage.park` returns and the car is a BMW or Audi with mileage, write these values
on the current span.*

## Highlights

- **OpenTelemetry-native**: enriches existing spans; no own backend, no competing spans.
- **Live**: configuration changes apply while the JVM runs; added or removed rules re-instrument
  already-loaded classes.
- **Operator-ready**: one extension jar, no extra `-javaagent`, works with a read-only root filesystem.
- **Safe**: never affects the application; invalid configuration never replaces a valid one.
- **Secure by construction**: the rule file is data, never code — hardened XML parsing, no way to
  call arbitrary methods, bounded input ([docs/06-config-xml.md](docs/06-config-xml.md) §6).
- **Fast**: 13 ns and 0 bytes per call when a condition is false; ~2.9 % median throughput overhead
  for the reference rule.
- **No leaks**: application classloaders stay collectable; proven by tests.

## Quick start (local)

Requirements: JDK 21, Maven 3.9.

```bash
mvn -DskipTests package

# OTLP receiver that writes spans to telemetry.log (or point the exporter at your collector)
java -cp dta-example-app/target/garage.jar otel.OtlpStub &

mkdir -p /tmp/dta-extensions && cp dta-extension/target/dynamic-trace-agent.jar /tmp/dta-extensions/
JAVA_TOOL_OPTIONS="-javaagent:dta-benchmarks/target/agent/opentelemetry-javaagent.jar" \
OTEL_JAVAAGENT_EXTENSIONS=/tmp/dta-extensions \
DYNAMIC_TRACE_CONFIG_FILE=$PWD/config/agent-config.xml \
OTEL_SERVICE_NAME=garage OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318 \
java -jar dta-example-app/target/garage.jar
```

Then send requests from `dta-example-app/car.http` and edit `config/agent-config.xml` while the
application runs.

## Deploy with the OpenTelemetry Operator

```yaml
spec:
  java:
    image: ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:2.30.0  # pinned
    extensions:
      - image: ghcr.io/gpcmol/dynamic-trace-agent:<version>
        dir: /extensions
```

Mount the rule ConfigMap as a directory (no `subPath`) and set `DYNAMIC_TRACE_CONFIG_FILE`.
Complete example: [`deploy/kubernetes/`](deploy/kubernetes).

| Variable | Meaning | Default |
|---|---|---|
| `DYNAMIC_TRACE_CONFIG_FILE` | rule file; not set → extension inactive | — |
| `DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL` | poll interval in seconds | `5` |
| `OTEL_JAVAAGENT_DEBUG` | also enables this extension's debug log | `false` |

## Documentation

| | |
|---|---|
| Writing rules | [docs/06-config-xml.md](docs/06-config-xml.md), [docs/07-dsl.md](docs/07-dsl.md) |
| How it works | [docs/02-architecture.md](docs/02-architecture.md), [docs/03-classloaders.md](docs/03-classloaders.md), [docs/04-flows.md](docs/04-flows.md) |
| Performance and memory | [docs/08-performance.md](docs/08-performance.md), [docs/09-memory-safety.md](docs/09-memory-safety.md), [benchmarks/](benchmarks) |
| Maintaining | [docs/10-code-guidelines.md](docs/10-code-guidelines.md), [docs/11-benchmarking.md](docs/11-benchmarking.md) |
| Everything | [docs/README.md](docs/README.md) |
| Requirements | [`openspec/specs/`](openspec/specs) |

## Development

```bash
mvn verify                                   # format, Error Prone, unit, architecture, memory and end-to-end tests
mvn spotless:apply                           # format
./lcm.sh --check                             # outdated dependencies / plugins (./lcm.sh updates them)
java -jar dta-benchmarks/target/benchmarks.jar -prof gc     # JMH
dta-benchmarks/scripts/bench.sh                             # k6 macro benchmark (needs k6, jq)
```

| Module | Content |
|---|---|
| `dta-core` | configuration, paths, condition DSL, plans, snapshots, bindings — JDK only |
| `dta-gateway` | the two classes shared with woven application code (bootstrap classloader) |
| `dta-extension` | the OpenTelemetry extension → `target/dynamic-trace-agent.jar` |
| `dta-example-app` | garage demo application + OTLP stub |
| `dta-integration-tests` | end-to-end tests with the real OpenTelemetry agent |
| `dta-benchmarks` | JMH and k6 benchmarks |

## Use cases

Each snippet is one `<rule>` inside `<dynamic>`; the `<static>` attributes of the file are written
too whenever a rule matches. Syntax: [docs/06-config-xml.md](docs/06-config-xml.md) (paths) and
[docs/07-dsl.md](docs/07-dsl.md) (`<when>`).

**Keep every trace of one customer during an incident** — no attributes needed: the static
`debug=true` is the tail-sampling marker.

```xml
<rule>
    <selector><class name="com.example.shop.OrderService"/><method name="placeOrder"/></selector>
    <when>$arg0.customer != null and $arg0.customer.id == 'C-1042'</when>
</rule>
```

**Explain failures**: record why an order was rejected, only when it was.

```xml
<rule>
    <selector><class name="com.example.shop.OrderService"/><method name="placeOrder"/></selector>
    <when>not $return.accepted</when>
    <attribute key="order.status" path="$return.status"/>
    <attribute key="order.reason" path="$return.rejection.code"/>
</rule>
```

**Large or unusual input**: numbers, arithmetic, sizes and set membership.

```xml
<rule>
    <selector><class name="com.example.shop.OrderService"/><method name="placeOrder"/></selector>
    <when>($arg0.total gt 10000 or size($arg0.lines) gte 50) and $arg0.country in ['NL', 'BE']</when>
    <attribute key="order.total"   path="$arg0.total"/>
    <attribute key="order.country" path="$arg0.country"/>
</rule>
```

**Text matching**: SQL-`LIKE` patterns, case-insensitive variants, tags in a collection.

```xml
<rule>
    <selector><class name="com.example.auth.LoginService"/><method name="login"/></selector>
    <when>ilike($arg0.email, '%@example.com') or contains($arg0.roles, 'admin')</when>
    <attribute key="user.email" path="$arg0.email"/>
    <attribute key="user.roles" path="$arg0.roles"/>
</rule>
```

**Collections**: one element by index, or one property of every element (written as an array).
Conditions know the element type from the generic signature (`List<Tree>` → `Tree`).

```xml
<rule>
    <selector><class name="com.example.forest.Forest"/><method name="getTree"/></selector>
    <when>size($return) gt 0 and $return[0].color == 'green'</when>
    <attribute key="tree.first.color" path="$return[0].color"/>   <!-- List<Tree> getTree() -->
    <attribute key="tree.colors"      path="$return.color"/>      <!-- colors of all trees -->
    <attribute key="tree.second.id"   path="$return[1].id"/>      <!-- absent with < 2 trees -->
</rule>
```

**Interfaces and several methods**: a selector on an interface covers every implementation; one
rule can have several selectors.

```xml
<rule>
    <selector><class name="com.example.pay.PaymentProvider"/><method name="authorize"/></selector>
    <selector><class name="com.example.pay.PaymentProvider"/><method name="capture"/></selector>
    <when>$arg0.amount gt 0 and $this.name != 'sandbox'</when>
    <attribute key="pay.provider" path="$this.name"/>
    <attribute key="pay.amount"   path="$arg0.amount"/>
    <attribute key="pay.currency" path="$arg0.currency"/>
</rule>
```
