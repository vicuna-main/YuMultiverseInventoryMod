package dev.yu.mwi.compat;

import java.util.List;

/** Aggregate result for one profile preparation pass. */
public record ItemCompatibilityReport(List<ItemCompatibilityIssue> issues, int aliasesApplied) {
    public ItemCompatibilityReport {
        issues = List.copyOf(issues);
        if (aliasesApplied < 0) {
            throw new IllegalArgumentException("aliasesApplied must not be negative");
        }
    }

    public static ItemCompatibilityReport empty() {
        return new ItemCompatibilityReport(List.of(), 0);
    }

    public boolean changed() {
        return !issues.isEmpty() || aliasesApplied > 0;
    }

    public long missingItems() {
        return issues.stream().filter(issue ->
                issue.reason() == ItemCompatibilityReason.MISSING_ITEM_REGISTRY_ENTRY).count();
    }

    public long missingComponents() {
        return issues.stream().filter(issue ->
                issue.reason() == ItemCompatibilityReason.MISSING_DATA_COMPONENT_TYPE).count();
    }
}
