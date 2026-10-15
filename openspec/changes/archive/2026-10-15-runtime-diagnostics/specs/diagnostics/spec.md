## ADDED Requirements

### Requirement: Explain silent runtime cases in debug mode
In debug mode the agent SHALL report once per rule, attribute and binding when an attribute has no
value (a `null` on the way, a missing property, an index out of range, a `Map` or a failing getter)
or a value of a type that cannot be written, and once per rule and binding when its condition
fails at runtime. The messages SHALL name the rule, `Class#method`, the attribute key and path, and
for an unwritable value its type, never the value. With debug off these cases SHALL stay silent and
SHALL add no logging cost.

#### Scenario: Attribute without value
- **WHEN** debug is on and `$arg0.broken` throws on every call of a method called three times
- **THEN** one debug message names the rule, the method and attribute `broken`, and the other
  attributes are written

#### Scenario: Unwritable value
- **WHEN** debug is on and an attribute path resolves to a `Passenger` object
- **THEN** one debug message names the attribute and the type `Passenger`, not the value

#### Scenario: Failing condition
- **WHEN** debug is on and a condition throws twice
- **THEN** it counts as `false` both times and one debug message is logged

#### Scenario: Debug off
- **WHEN** debug is off and attributes have no value
- **THEN** nothing is logged
