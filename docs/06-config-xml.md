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
- Classes in `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `io.opentelemetry.`, `net.bytebuddy.`
  and `org.otel.agent.` cannot be selected: the rule is ignored with a warning.

## 4. Paths

```
$this | $return | $argN   ( .property ( [index] )? )+        N = 0..127
```

| Example | Value |
|---|---|
| `$arg0.brand` | `getBrand()`, else `isBrand()` (boolean), else record accessor `brand()`, else public field |
| `$arg0.passengers[1].name` | name of the second passenger (array, `List` or any `Iterable`) |
| `$arg0.passengers.name` | list of all passenger names (property on a collection = for each element) |
| `$return.status` | from the return value; `null` for void methods |

`null` anywhere on the way, a `Map`, a missing property or a throwing getter → no attribute.

Value types written: string, long (all integer types), double, boolean, homogeneous arrays of
those; `char` → string, enum → `name()`. Other objects are not written (no `toString()`).

## 5. Validation and errors

| Problem | Effect | Example message |
|---|---|---|
| malformed XML, DOCTYPE, external entity | document rejected | `malformed XML at line 3, column 3: The element type "static" must be terminated …` |
| unknown element / XML attribute, namespace | document rejected | `<configuration>/<dynamic>/<rule>#1/<selector>#1: unknown element <klass>` |
| duplicate section, blank required value | document rejected | `<configuration>: <static> may occur only once` |
| duplicate attribute key (static and dynamic share one key space), key > 255 | document rejected | `rule #2: attribute key 'brand' is used more than once in the document` |
| invalid path syntax | document rejected | `rule #1, attribute 'brand': invalid path '$arg0..brand': empty segment` |
| protected namespace | rule ignored | `rule #1 (java.util.ArrayList#add) is ignored: … protected namespace 'java.*'` |
| condition does not parse | rule ignored | `rule #2 (a.B#m) is ignored: condition: syntax error: '&&' is not supported, use 'and' at position 8` |
| method / property does not exist, condition type error | rule ignored for that class | `rule #1 (…) is ignored for com.example.Garage: attribute 'x': 'missing' is not a property of com.example.Result` |
| selector class not loaded (typo?) | warning only; rule applies if it loads | `selected class com.example.Garag is not found or not loaded yet …` |

A rejected document never replaces the active configuration.
