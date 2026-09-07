# Yu Multiverse Inventory 1.0.4

## Hunger self-repair

- Automatically repairs negative, non-finite, or corruption-scale hunger exhaustion to `0`.
- Applies the repaired payload back to live `FoodData`, preventing vanilla player data from carrying
  the same anomaly into the next login.
- Logs the player name/UUID, capture or apply phase, repair reason, original value, and repaired value.
- Continues login, checkpoint, and world-transition transactions without quarantining or disconnecting
  the player for this recoverable scalar anomaly.
- Keeps structural payload validation strict for missing fields, incorrect NBT types, and unrelated
  hunger corruption.
