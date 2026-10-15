## 1. Generic types

- [x] 1.1 Add `GenericTypes` (`erase`, `elementType`, `resolve`) with private `ParameterizedType` / `GenericArrayType` implementations
- [x] 1.2 `GenericTypesTest`: `List<Tree>`, `ArrayList<Tree>`, `class Trees extends ArrayList<Tree>`, `Iterable<T>` via interface, `List<? extends Tree>`, `List<?>`, raw `List`, `T[]`, `T extends Tree`, variable resolved through a generic superclass
- [x] 1.3 `PropertyAccessors.propertyGenericType` mirroring `propertyType`
- [x] 1.4 `ExitSignature`: generic parameter and return types with a raw-class convenience constructor; `RuleBinder` passes the generic signature

## 2. Typing

- [x] 2.1 `ConditionTyper.staticType` walks `Type`; root types resolved against the receiver; property types resolved against the current type; index uses `GenericTypes.elementType`
- [x] 2.2 Remove `erased collection type` for indexes; keep it for a property on a collection
- [x] 2.3 `ValueNode.Coerced` and coercion of `Object` operands in comparison, membership, like/ilike, truthy and arithmetic
- [x] 2.4 Update the typing rules in the `ConditionTyper` Javadoc

## 3. Tests

- [x] 3.1 `ConditionTest`: generic list element typed (`$arg0.passengers[1].name == 'Jan'`), typo after generic element → `unknown property`, dynamic element comparisons (String, Long from Integer, Double, Boolean, wrong runtime type → false), `in` and `like` on dynamic values, two dynamic operands, arithmetic with dynamic side
- [x] 3.2 Update existing expectations that relied on `erased collection type` for indexes (`ConditionTest`, `RuleBinderTest`)
- [x] 3.3 `RuleBinderTest`: `List<T>` declared in a generic superclass resolved on the subclass; `List<?>` return compiles and evaluates
- [x] 3.4 Static conditions contain no `Coerced` node

## 4. Performance

- [x] 4.1 JMH `evaluateDynamicCondition` in `PathAndConditionBenchmark`
- [x] 4.2 Compare `EngineBenchmark` and `PathAndConditionBenchmark` with the baseline; record results in `benchmarks/`

## 5. Documentation and specs

- [x] 5.1 `docs/07-dsl.md`: types table (generic elements, dynamic values), coercion rules, failures table, known limits, examples
- [x] 5.2 README use cases: condition on a list element
- [x] 5.3 `openspec/BACKLOG.md`: remove generic element-type inference

## 6. Verification

- [x] 6.1 `mvn verify` passes
- [x] 6.2 `openspec validate condition-element-types --strict` passes
- [x] 6.3 Every delta-spec scenario has a test; docs, Javadoc and spec agree
