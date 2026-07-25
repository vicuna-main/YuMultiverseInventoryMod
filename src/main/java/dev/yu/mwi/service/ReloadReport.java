package dev.yu.mwi.service;

import java.util.List;

public record ReloadReport(int onlinePlayersAffected, int changedShares, List<String> details) {
    public ReloadReport {
        details = List.copyOf(details);
    }

    public boolean changesProfiles() {
        return changedShares > 0;
    }
}
