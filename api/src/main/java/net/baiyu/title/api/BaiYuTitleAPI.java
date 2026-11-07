package net.baiyu.title.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The BaiYuTitleAPI class is the main entry point for accessing the BaiYuTitle API.
 * Other plugins can use this API to interact with the title system.
 */
public abstract class BaiYuTitleAPI {
    private static BaiYuTitleAPI INSTANCE;

    /**
     * Gets the instance of the BaiYuTitleAPI.
     *
     * @return the instance of BaiYuTitleAPI
     * @since 0.1.0
     */
    public static BaiYuTitleAPI getInstance() {
        if (INSTANCE == null)
            throw new RuntimeException("API was accessed before being initialized!");
        return INSTANCE;
    }

    /**
     * Sets the instance of the BaiYuTitleAPI.
     * This method is intended for internal use by the api provider only.
     *
     * @param api the instance of BaiYuTitleAPI to set
     * @since 0.1.0
     */
    @ApiStatus.Internal
    protected static void setInstance(BaiYuTitleAPI api) {
        INSTANCE = api;
    }

    /**
     * Get the current show title name for a player.
     *
     * @param playerUuid the player's UUID
     * @return the title name, or null if no title is equipped
     * @since 0.1.0
     */
    @Nullable
    public abstract String getShowTitle(@NotNull UUID playerUuid);

    /**
     * Get the number of titles a player owns.
     *
     * @param playerUuid the player's UUID
     * @return the number of titles
     * @since 0.1.0
     */
    public abstract int getTitleCount(@NotNull UUID playerUuid);

    /**
     * Get a player's title coin balance.
     *
     * @param playerUuid the player's UUID
     * @return the coin balance
     * @since 0.1.0
     */
    public abstract int getCoinBalance(@NotNull UUID playerUuid);

    /**
     * Check if a player owns a specific title.
     *
     * @param playerUuid the player's UUID
     * @param titleId    the title ID
     * @return true if the player owns the title
     * @since 0.1.0
     */
    public abstract boolean ownsTitle(@NotNull UUID playerUuid, int titleId);
}
