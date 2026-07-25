package dev.yu.mwi.service;

import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.WorldIdentity;
import dev.yu.mwi.storage.StoredProfile;

record ProfileCandidate(WorldIdentity world, ProfileKey key, StoredProfile profile) {
}
