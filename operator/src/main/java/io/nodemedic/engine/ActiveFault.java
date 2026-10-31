package io.nodemedic.engine;

import io.nodemedic.crd.Severity;

import java.time.Instant;

/**
 * A fault currently reported on a node: a watched condition with status "True".
 * Identified by (type, at), where {@code at} is the condition's lastTransitionTime.
 */
public record ActiveFault(String type, Severity severity, Instant at) {
}
