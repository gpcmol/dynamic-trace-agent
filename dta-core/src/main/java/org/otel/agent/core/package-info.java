/**
 * Pure logic of the agent: configuration parsing, paths, the condition DSL, transformation plans,
 * snapshots and class bindings.
 *
 * <p>Depends on the JDK only (enforced by the Maven enforcer and ArchUnit), so everything in here
 * is testable with plain JUnit. See {@code docs/02-architecture.md}.
 */
package org.otel.agent.core;
