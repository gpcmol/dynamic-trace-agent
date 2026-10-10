# 07 — Condition language (`<when>`)

A rule's optional `<when>` decides, at every exit of a selected method, whether the rule enriches
the span. No `<when>` (or `<when>true</when>`) means: always.

```xml
<when>$arg0.brand in ['bmw', 'audi'] and ilike($arg0.model, 'x%') and $arg0.mileage gt 0</when>
```

Code: `dta-core`, package `org.otel.agent.core.dsl`. Normative rules: spec `condition-dsl`.

---

## 1. Life of a condition

```
 "<when> text"
      │  ExpressionParser (+ Lexer)            once per configuration load
      ▼                                          syntax errors → rule disabled, 1 warning
 Expression  (untyped tree, no Class refs)     lives in the configuration snapshot
      │  ConditionTyper(signature, owner)      once per (rule, application class)
      ▼                                          type errors   → rule disabled, 1 warning
 Condition   (typed, executable)               lives in that class's binding
      │  Condition.evaluate(condition, frame)  every method exit — never throws
      ▼
 true / false
```

Nothing is parsed, looked up by name or type-guessed on the hot path.

---

## 2. Grammar

All operators are XML-safe: no `&`, `<` or `>` is needed, so the expression is written verbatim
inside `<when>`.

```
expression := or
or         := and ( 'or' and )*
and        := not ( 'and' not )*
not        := 'not' not | predicate
predicate  := '(' expression ')'
            | textFn '(' sum ',' sum ')'                  textFn := contains | icontains | like | ilike
            | sum ( compareOp sum | 'in' '[' literal ( ',' literal )* ']' )?
sum        := product ( ( '+' | '-' ) product )*
product    := unary ( ( '*' | '/' | '%' ) unary )*
unary      := '-' NUMBER | atom
atom       := NUMBER | STRING | 'true' | 'false' | 'null' | PATH
            | 'size' '(' sum ')' | '(' sum ')'
compareOp  := '==' | '!=' | 'lt' | 'lte' | 'gt' | 'gte'
literal    := [ '-' ] NUMBER | STRING | 'true' | 'false' | 'null'

PATH       := '$this' | '$return' | '$arg' N  ( '.' property ( '[' index ']' )? )*
STRING     := '…' | "…"            (no escapes; use the other quote to include one)
NUMBER     := digits | digits '.' digits
```

Precedence, lowest first: `or` · `and` · `not` · comparison / `in` · `+ -` · `* / %`.
A predicate without operator (`$arg0.electric`) must be boolean and means `== true`.

| Rejected form | Use instead |
|---|---|
| `&&`, `\|\|`, `!x` | `and`, `or`, `not x` |
| `<`, `<=`, `>`, `>=` | `lt`, `lte`, `gt`, `gte` |
| `=` | `==` |

Keywords are lowercase and case-sensitive.

---

## 3. Types

Every operand gets a static type when the rule binds to a class:

| Source | Static type |
|---|---|
| `$this` | the runtime class of the receiver |
| `$argN` | declared parameter type (`Object` if the method has fewer parameters) |
| `$return` | declared return type (`Object` for `void` — value is always `null`) |
| `.property` | return type of `getX()` / `isX()`, or the type of public field `x` |
| `[i]` | component type of an array; for a `List`/`Iterable` see "erased collection type" |
| literals | `String`, `Long` (integers), `Double` (decimals), `Boolean`, null |

Normalisation: `byte short int long` (and wrappers) → **Long**; `float double` → **Double**;
`boolean` → **Boolean**. A value of static type `Object` is not checked further.

| Operation | Allowed operand types |
|---|---|
| `==` `!=` | both sides the same normalised type, or one side `null` |
| `lt lte gt gte` | both `Long` or both `Double` (no mixing: `$x gt 1.5` with a long `x` is a type mismatch) |
| `in [ … ]` | value `String`/`Long`/`Double`/`Boolean`; every literal of that type (or `null`) |
| `like` `ilike` | `(String, String)` |
| `contains` `icontains` | `(String or Collection or Object, String)` |
| `size(x)` | `Collection`, array (or `Object`) → `Long` |
| `+ - * / %` | numbers; result `Double` if either side is `Double`, else `Long` |

---

## 4. Evaluation

| Situation | Result |
|---|---|
| `x == null` / `x != null` (null **literal**) | tests the reference |
| any other operation with a `null` operand | `false` |
| `size(null)` | `0` |
| arithmetic with `null`, integer `/ 0` or `% 0` | `null` (so the comparison is `false`) |
| `double / 0` | `Infinity` (Java semantics) |
| `and` / `or` | short-circuit, left to right |
| any runtime failure (e.g. a getter throws) | `false` — the rule is skipped for this call |

Functions:

| Function | Meaning |
|---|---|
| `contains(text, part)` | `text.contains(part)` — substring |
| `contains(collection, x)` | `collection.contains(x)` |
| `icontains(text, other)` | `text.equalsIgnoreCase(other)` — **whole string, not substring** |
| `icontains(collection, text)` | any element `equalsIgnoreCase(text)` |
| `like(text, pattern)` | SQL LIKE: `%` any sequence, `_` one character, case-sensitive |
| `ilike(text, pattern)` | as `like`, ASCII case-insensitive (ß, Turkish İ are not folded) |
| `size(x)` | number of elements |

For a case-insensitive substring search use `ilike(text, '%part%')`.

`in` is set membership, not a range: `$arg0.year in [2023, 2024]`. Numeric values are compared
after normalisation, so an `int` property matches.

---

## 5. Failures

A condition that does not compile disables **its rule only**; the rest of the configuration stays
active. One warning names the rule and the category:

| Category | Typical cause |
|---|---|
| `syntax error` | unclosed parenthesis or string, `&&`, unknown character, invalid path |
| `type mismatch` | `$arg0.brand == 42`, `$arg0.mileage gt 1.5`, non-boolean used as condition |
| `unknown function` | `length($arg0.brand)` |
| `unknown property` | `$arg0.colour` when `Car` has no `getColour()`/`isColour()`/`colour` |
| `erased collection type` | `$arg0.passengers[1].name` — element type of a generic `List` is unknown |
| `map unsupported` | `$arg0.labels.key` when `labels` is a `Map` |
| `wrong arity` | `size(a, b)`, `like(a)` |

Known limits (see `openspec/BACKLOG.md`): no `Map` access, no generic element-type inference for
collections in conditions, no mixed `Long`/`Double` comparisons.

---

## 6. Examples

```
$arg0.brand == 'bmw'
$arg0.brand == 'bmw' and ilike($arg0.model, 'x%')
$arg0.brand in ['bmw', 'audi', 'vw'] and $arg0.mileage gt 0
size($arg0.passengers) gt 2 or contains($arg0.tags, 'vip')
not ($arg0.brand == 'audi') and $arg0.electric
($arg0.mileage + 500) / 2 gte 10000
$return.accepted and $return.status != 'rejected'
$arg0.seats[0].name == 'Kees'
$arg0.customer != null and $arg0.customer.country == 'NL'
```
