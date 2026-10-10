## ADDED Requirements

### Requirement: Single XML configuration file
The agent SHALL read its configuration from exactly one XML file named by the environment variable
`DYNAMIC_TRACE_CONFIG_FILE`. No other configuration source (env-embedded XML, web server, TTL)
SHALL exist.

#### Scenario: Variable absent
- **WHEN** `DYNAMIC_TRACE_CONFIG_FILE` is not set or blank
- **THEN** the agent stays disabled without logging an error

### Requirement: Document shape
The root element SHALL be `<configuration>` with at most one `<static>` and at most one
`<dynamic>` section, both optional.
- `<static>` contains zero or more `<attribute key="…" value="…"/>`. Static attributes are
  **global**: they are written on every enrichment event of every rule.
- `<dynamic>` contains zero or more `<rule>`. A rule contains one or more `<selector>` (each with
  exactly one `<class name="…"/>` and one `<method name="…"/>`), at most one optional `<when>`, and
  **zero or more** `<attribute key="…" path="…"/>`.

#### Scenario: Valid minimal document
- **WHEN** the file contains `<configuration/>`
- **THEN** it is valid and means "no instrumentation" (explicit disable)

#### Scenario: Marker-only rule
- **WHEN** a rule has a selector and a `<when>` but no `<attribute>` elements
- **THEN** it is valid and, when the condition holds, writes only the global static attributes
  (e.g. `debug=true` for the tail sampler)

### Requirement: Strict validation
The parser SHALL reject: unknown elements, unknown XML attributes, any namespace, duplicate
`<static>`/`<dynamic>` sections, blank required values, any element in `<dynamic>` other than
`<rule>`, a rule without selector, and a `(class, method)` pair used more than once anywhere in the
document. Attribute keys SHALL be unique across static and dynamic
attributes together and at most 255 characters; keys and values are not trimmed.

#### Scenario: Unknown element
- **WHEN** `<dynamic>` contains an element other than `<rule>`
- **THEN** the document is rejected with a message naming the element and its location

#### Scenario: Duplicate key across sections
- **WHEN** a static attribute and a dynamic attribute share the key `brand`
- **THEN** the document is rejected with a message naming the key

### Requirement: Secure XML parsing
Parsing SHALL use the JDK XML parser with secure processing on, DOCTYPE declarations disallowed,
and external entities, external DTDs and external schemas disabled.

#### Scenario: XXE attempt
- **WHEN** the document declares a DOCTYPE or external entity
- **THEN** it is rejected and no file or network resource is read

### Requirement: Selector semantics
Selectors within a rule SHALL be OR alternatives and criteria within a selector SHALL be AND
constraints. A selector names a class, abstract class or interface and a **public** method on it
(declared or inherited). Overloads are not distinguishable: all public overloads with that name
are selected.

#### Scenario: Interface selector
- **WHEN** a selector names an interface method
- **THEN** implementations of that method in loaded implementing classes are selected

### Requirement: Non-existent targets are ignored per rule
The agent SHALL silently ignore a rule (enrich nothing for it, keep all other rules active) when it
refers to something that does not exist in the running application: a selector method that is not
a public method of the selected class, or a property in a `path` or `<when>` that does not exist on
the statically known type. The agent SHALL log exactly one warning per rule and cause, whenever the
problem is detected (at load, reload or when the class is first used). This is not a document error.

#### Scenario: Unknown method
- **WHEN** a selector names `com.example.Garage#parkk` and `Garage` has no public method `parkk`
- **THEN** that rule is ignored, one warning names the rule and the missing method, and the other
  rules work normally

#### Scenario: Unknown property
- **WHEN** a rule has `path="$arg0.colour"` and `Car` has no property `colour`
- **THEN** that rule is ignored with one warning; other rules are unaffected

### Requirement: Selector class not loaded
The agent SHALL, after each successful load or reload, log one warning per selector whose class matches
no currently loaded class ("not found or not loaded yet"), and SHALL apply the rule automatically if
the class loads later, because a class that does not exist cannot be distinguished from one that is
not loaded yet.

#### Scenario: Typo in class name
- **WHEN** a selector names `com.example.Garag` and no such class is ever loaded
- **THEN** one warning is logged after the load, the rule never enriches, and the application is unaffected

