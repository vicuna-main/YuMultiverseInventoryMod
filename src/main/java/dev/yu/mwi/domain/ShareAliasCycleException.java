package dev.yu.mwi.domain;

import java.util.List;

/** Raised when recursive aliases form a cycle. */
public final class ShareAliasCycleException extends IllegalArgumentException {
    private final List<String> cycle;

    public ShareAliasCycleException(List<String> cycle) {
        super("share alias cycle: " + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    public List<String> cycle() {
        return cycle;
    }
}
