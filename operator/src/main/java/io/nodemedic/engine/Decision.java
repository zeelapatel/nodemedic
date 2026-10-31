package io.nodemedic.engine;

import io.nodemedic.crd.FaultRecord;

import java.util.List;

/**
 * What the shell should do for one node. Pure data: the evaluator never touches Kubernetes.
 *
 * <ul>
 *   <li>{@code NONE}: nothing to write.</li>
 *   <li>{@code CREATE_SUSPECT}: no NodeRemediation exists yet; create it in Suspect with {@code faults}.</li>
 *   <li>{@code UPDATE}: write {@code faults} (and {@code message} if not null) to status; phase unchanged.</li>
 *   <li>{@code ADMIT}: write {@code faults}, clear the message, move Suspect to Cordoned.</li>
 *   <li>{@code RELEASE}: delete the NodeRemediation (back to Healthy).</li>
 * </ul>
 * {@code message == null} means "leave the message as it is"; {@code ""} means "clear it".
 */
public record Decision(Action action, List<FaultRecord> faults, String message) {

    public enum Action { NONE, CREATE_SUSPECT, UPDATE, ADMIT, RELEASE }

    public static final String WAITING_FOR_BUDGET = "waiting for remediation budget";

    public static Decision none() {
        return new Decision(Action.NONE, List.of(), null);
    }

    public static Decision createSuspect(List<FaultRecord> faults) {
        return new Decision(Action.CREATE_SUSPECT, faults, "");
    }

    public static Decision update(List<FaultRecord> faults, String message) {
        return new Decision(Action.UPDATE, faults, message);
    }

    public static Decision admit(List<FaultRecord> faults) {
        return new Decision(Action.ADMIT, faults, "");
    }

    public static Decision release() {
        return new Decision(Action.RELEASE, List.of(), null);
    }
}
