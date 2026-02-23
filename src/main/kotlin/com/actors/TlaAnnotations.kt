package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * TLA+ BRIDGE ANNOTATIONS
 * ═══════════════════════════════════════════════════════════════════
 *
 * Machine-readable annotations linking Kotlin implementation to TLA+
 * specification elements. These serve as:
 *   1. Documentation: which spec element each code construct implements
 *   2. Traceability: formal verification coverage tracking
 *   3. Future tooling: automated conformance checking
 */

/**
 * Links a Kotlin field/property to a TLA+ VARIABLE.
 * Example: @TlaVariable("mailbox") on Channel<M> field
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class TlaVariable(val name: String)

/**
 * Links a Kotlin function to one or more TLA+ actions.
 * Example: @TlaAction("Send") on suspend fun send(msg)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TlaAction(val names: String)

/**
 * Links a Kotlin function to a TLA+ invariant.
 * Example: @TlaInvariant("BoundedCapacity") on checkBounded()
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TlaInvariant(val name: String)

/**
 * Associates a Kotlin class with a TLA+ module.
 * Example: @TlaSpec("ActorMailbox") on Mailbox class
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TlaSpec(val module: String)
