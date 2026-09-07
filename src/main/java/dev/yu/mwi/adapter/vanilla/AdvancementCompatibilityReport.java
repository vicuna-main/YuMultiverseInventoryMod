package dev.yu.mwi.adapter.vanilla;

import java.util.List;

/** Aggregated, bounded diagnostics for one decoded advancement profile. */
public record AdvancementCompatibilityReport(
        int staleAdvancementsSkipped,
        int staleCriteriaSkipped,
        int advancementAliasesMigrated,
        int criterionAliasesMigrated,
        List<String> advancementSamples,
        List<String> criterionSamples
) {
    public AdvancementCompatibilityReport {
        if (staleAdvancementsSkipped < 0 || staleCriteriaSkipped < 0
                || advancementAliasesMigrated < 0 || criterionAliasesMigrated < 0) {
            throw new IllegalArgumentException("advancement compatibility counts must not be negative");
        }
        advancementSamples = List.copyOf(advancementSamples);
        criterionSamples = List.copyOf(criterionSamples);
    }

    public boolean changed() {
        return staleAdvancementsSkipped > 0
                || staleCriteriaSkipped > 0
                || advancementAliasesMigrated > 0
                || criterionAliasesMigrated > 0;
    }
}
