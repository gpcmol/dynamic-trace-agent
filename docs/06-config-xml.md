# 06 — Configuration reference (XML)

Spec `xml-configuration`. Parser: `org.otel.agent.core.config.XmlConfigurationParser`.

## 1. Where the file comes from

| Variable | Meaning | Default |
|---|---|---|
| `DYNAMIC_TRACE_CONFIG_FILE` | path of the XML file; not set → extension inactive | — |
| `DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL` | poll interval in seconds (positive integer) | `5` |

In Kubernetes mount a ConfigMap as a **directory** (no `subPath`), see `deploy/kubernetes/`.

## 2. Shape

```xml
<configuration>
    <static>                                     <!-- optional, at most once -->
        <attribute key="debug" value="true"/>    <!-- written on EVERY enrichment event -->
    </static>
    <dynamic>                                    <!-- optional, at most once -->
        <rule>                                   <!-- zero or more -->
            <selector>                           <!-- one or more: OR alternatives -->
                <class name="com.example.Garage"/>
                <method name="park"/>
            </selector>
            <when>$arg0.brand == 'bmw'</when>    <!-- optional: docs/07-dsl.md -->
            <attribute key="brand" path="$arg0.brand"/>   <!-- zero or more -->
        </rule>
    </dynamic>
</configuration>
```

`<configuration/>` is valid and means: no instrumentation (advice is removed).

A rule without `<attribute>` is valid: when its condition holds, only the static attributes are
written — useful for a tail-sampling marker such as `debug=true`.

## 3. Selectors

- `class`: fully qualified binary name (`com.example.Outer$Inner` for nested classes); a class,
  abstract class or interface. Subclasses and implementations are selected too.
- `method`: a **public** instance method name; all public overloads with that name are selected.
- A `(class, method)` pair may occur only once in the whole document.
- Classes in `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `org.w3c.`, `org.xml.`,
  `io.opentelemetry.`, `net.bytebuddy.` and `org.otel.agent.` cannot be selected: the rule is
  ignored with a warning.

## 4. Paths

```
( $this | $return | $argN ) ( [index] )?  ( .property ( [index] )? )*     N = 0..127
```

An attribute path needs at least one property or a root index: `$return` alone is invalid,
`$return[0]` is valid.

| Example | Value |
|---|---|
| `$arg0.brand` | `getBrand()`, else `isBrand()` (boolean), else record accessor `brand()`, else public field |
| `$arg0.passengers[1].name` | name of the second passenger (array, `List` or any `Iterable`) |
| `$arg0.passengers.name` | list of all passenger names (property on a collection = for each element) |
| `$return.status` | from the return value; `null` for void methods |
| `$return[0].color` | color of the first element when the method returns an array, `List` or `Iterable` |
| `$arg1[2]` | the third element of the second argument itself |
| `$return.color` | when the method returns a collection: the colors of **all** elements |

`null` anywhere on the way, a `Map`, a missing property, a throwing getter, an index out of range or
an index on a value that is no array or `Iterable` → no attribute.

Limits: a path has at most 32 segments (a root index does not count); an index is 0..999 999 999;
of an array or collection only the first 1 000 elements are read (and written), and an `Iterable`
that is not a `Collection` is walked at most 1 000 elements for an index. The property `class` and
properties of protected JDK types cannot be read, see §6.

Value types written: string, long (all integer types), double, boolean, homogeneous arrays of
those; `char` → string, enum → `name()`. Other objects are not written (no `toString()`).

## 5. Validation and errors

| Problem | Effect | Example message |
|---|---|---|
| malformed XML, DOCTYPE, external entity | document rejected | `malformed XML at line 3, column 3: The element type "static" must be terminated …` |
| unknown element / XML attribute, namespace | document rejected | `<configuration>/<dynamic>/<rule>#1/<selector>#1: unknown element <klass>` |
| duplicate section, blank required value | document rejected | `<configuration>: <static> may occur only once` |
| duplicate attribute key (static and dynamic share one key space), key > 255, control character in a key | document rejected | `rule #2: attribute key 'brand' is used more than once in the document` |
| invalid path syntax (also `$return[01]`, `$return[0][1]`), `class` in a path, more than 32 segments | document rejected | `rule #1, attribute 'brand': invalid path '$arg0..brand': empty segment` |
| file larger than 1 MiB, not a regular file, not UTF-8 | file not loaded | `configuration file … cannot be read (… larger than 1048576 bytes); keeping the current configuration and retrying` |
| protected namespace | rule ignored | `rule #1 (java.util.ArrayList#add) is ignored: … protected namespace 'java.*'` |
| condition does not parse, is longer than 4 096 characters or nested deeper than 64 levels | rule ignored | `rule #2 (a.B#m) is ignored: condition: syntax error: '&&' is not supported, use 'and' at position 8` |
| property of a protected JDK type | rule ignored for that class (condition) / no attribute | `… 'connection' cannot be read from javax.sql.DataSource (protected type)` |
| method / property does not exist, condition type error | rule ignored for that class | `rule #1 (…) is ignored for com.example.Garage: attribute 'x': 'missing' is not a property of com.example.Result` |
| selector class not loaded (typo?) | warning only; rule applies if it loads | `selected class com.example.Garag is not found or not loaded yet …` |

A rejected document never replaces the active configuration.

## 6. Security

**Trust model.** Whoever can change the rule file decides which values of the running application
end up in telemetry. Treat it like application configuration: restrict write access to the
ConfigMap (Kubernetes RBAC) and review changes. The agent makes sure that the file can do *only*
that — select public methods and read values — and nothing else:

| Threat | Defence | Code |
|---|---|---|
| XML attacks: external entities (XXE), entity expansion, XInclude, external DTD/schema | DOCTYPE is refused, secure processing, no external access; namespaces and unknown elements/attributes are rejected | `SecureXmlReader`, `StrictElement` |
| Executing something from the file | The file is data. Class and method names must be plain Java names and are only *compared* with loaded classes; a path is `root.property[index]` and nothing else; the condition language has no method calls, no assignments and four fixed functions. Nothing is evaluated by a script engine, expression library or reflection-by-name on arbitrary methods | `XmlConfigurationParser`, `PathCompiler`, `ExpressionParser` |
| Using a path to reach the JVM itself or to change state | `class` is never a property. Values of these JDK types (and their subtypes) have no properties: `Class`, `ClassLoader`, `Module`, `Thread`, `Runtime`, `Process`, reflection and method-handle objects, streams/readers/writers, channels, sockets, `URLConnection`, JDBC objects (`Connection`, `Statement`, `ResultSet`, `DataSource`), keys and key stores, `AtomicInteger`/`AtomicLong` (`getAndIncrement()`) | `ForbiddenTypes`, `PropertyAccessors` |
| Instrumenting the JVM or the agent | Protected namespaces (§3) | `ProtectedNamespaces` |
| Exhausting memory, CPU or stack | File ≤ 1 MiB and a regular file; `<when>` ≤ 4 096 characters and ≤ 64 nesting levels; path ≤ 32 segments; ≤ 1 000 elements read per collection (also for endless or lazily loaded ones); no regular expressions (`like` is linear) | `ConfigFileWatcher`, `ExpressionParser`, `PathCompiler`, `PathReader`, `AttributeValues` |
| Forging log lines or terminal escapes through keys, paths or conditions quoted in warnings | Control and invisible formatting characters are escaped and long messages are cut; attribute keys may not contain control characters | `AgentLog` |
| Leaking personal data into logs | Attribute values are never logged | `AgentLog` |

What remains by design: a path calls the application's **own** public getters (`getX()`, `isX()`,
record accessors) on the application thread. A getter with side effects (lazy loading, a remote
call) runs those side effects, and a rule can copy any value a getter chain reaches — including
secrets — into spans. That is the feature; it is why write access to the rule file must be
restricted. To bound the size of what is written, set `OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT`.
