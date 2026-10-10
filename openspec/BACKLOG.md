# Backlog — future ideas

| Idea | Notes |
|---|---|
| `Map` traversal in paths and conditions | e.g. `$arg0.attributes['key']` |
| Generic element-type inference (`List<Passenger>`) in conditions | lifts the "erased collection type" limitation |
| Mixed `Long`/`Double` comparisons and complete arithmetic type propagation | |
| Overload selection (signature in a selector) | e.g. `<method name="park" parameters="com.example.Car"/>` |
| Package / annotation / interface-wide / wildcard selectors | |
| Non-ASCII case folding for `ilike` (ß, Turkish İ) | |
| Primitive-typed readers for numeric leaves | removes boxing when a numeric comparison is reached (docs/08-performance.md §3) |
| Switch to disable the fallback span | for high-volume methods called outside traces |
| Macro scaling series over 1/10/100 selected methods; multi-hour soak with heap/metaspace over time | needs a generated application |
| JMX management | |
