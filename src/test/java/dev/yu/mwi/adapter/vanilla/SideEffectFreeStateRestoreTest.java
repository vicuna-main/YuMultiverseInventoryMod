package dev.yu.mwi.adapter.vanilla;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideEffectFreeStateRestoreTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/yu/mwi/adapter/vanilla");

    @Test
    void advancementRestoreNeverUsesGameplayAwardOrRevokeApis() throws Exception {
        String source = Files.readString(SOURCE.resolve("AdvancementAdapter.java"));
        assertFalse(source.contains("advancements.award("));
        assertFalse(source.contains("advancements.revoke("));
        assertFalse(source.contains("progress.grantProgress("));
        assertFalse(source.contains("progress.revokeProgress("));
        assertTrue(source.contains("progress.criteria.put("));
        assertTrue(source.contains("criterionProgress.getObtained()"));
        assertTrue(source.contains("Instant.EPOCH"));
        assertFalse(source.contains("Instant.now()"));
    }

    @Test
    void effectRecipeStatisticAndSpawnRestoreAvoidsEventStyleMutators() throws Exception {
        String effects = Files.readString(SOURCE.resolve("PotionEffectsAdapter.java"));
        assertFalse(effects.contains("player.addEffect("));
        assertFalse(effects.contains("player.forceAddEffect("));
        assertFalse(effects.contains("player.removeAllEffects("));

        String recipes = Files.readString(SOURCE.resolve("RecipeBookAdapter.java"));
        assertFalse(recipes.contains("player.resetRecipes("));
        assertFalse(recipes.contains("player.getRecipeBook().fromNbt("));
        assertTrue(recipes.contains("new ServerRecipeBook()"));
        assertTrue(recipes.contains("copyOverData(replacement)"));
        assertTrue(recipes.contains("sameSnapshot(actual, expected)"));
        assertTrue(recipes.contains("canonicalSnapshot(player.getRecipeBook().toNbt())"));
        assertFalse(recipes.contains("actual.equals(expected)"));
        assertTrue(recipes.contains("sendInitialRecipeBook(player)"));

        String statistics = Files.readString(SOURCE.resolve("StatisticsAdapter.java"));
        assertFalse(statistics.contains("player.resetStat("));
        assertFalse(statistics.contains("player.awardStat("));

        String respawn = Files.readString(SOURCE.resolve("RespawnLocationAdapter.java"));
        assertFalse(respawn.contains("player.setRespawnPosition("));
    }

    @Test
    void accessTransformerExposesOnlyFieldsNeededForSilentReplacement() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"));
        String transformer = Files.readString(Path.of("src/main/resources/META-INF/accesstransformer.cfg"));

        assertTrue(gradle.contains("minecraft.accessTransformers.file"));
        assertTrue(transformer.contains("PlayerAdvancements isFirstPacket"));
        assertTrue(transformer.contains("PlayerAdvancements registerListeners"));
        assertTrue(transformer.contains("AdvancementProgress criteria"));
        assertTrue(transformer.contains("LivingEntity effectsDirty"));
        assertTrue(transformer.contains("LivingEntity detectEquipmentUpdates()V"));
        assertTrue(transformer.contains("ServerPlayer respawnPosition"));
    }

    @Test
    void normalSuspensionResumeDoesNotRemoveAlreadyEmptyListenersAgain() throws Exception {
        String controller = Files.readString(SOURCE.resolve("AdvancementStateController.java"));
        String afterSuspend = controller.substring(
                controller.indexOf("public static void resumeListenersAfterSuspend"),
                controller.indexOf("public static void resumeAndSynchronizeFullState",
                        controller.indexOf("public static void resumeListenersAfterSuspend")));

        assertTrue(afterSuspend.contains("registerListeners("));
        assertFalse(afterSuspend.contains("stopListening()"));
    }
}
