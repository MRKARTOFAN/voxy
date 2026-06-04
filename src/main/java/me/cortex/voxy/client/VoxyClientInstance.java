package me.cortex.voxy.client;

import me.cortex.voxy.client.compat.FlashbackCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private final Config config;
    private final Path basePath;
    private final boolean noIngestOverride;
    public VoxyClientInstance() {
        super();
        var path = FlashbackCompat.getReplayStoragePath();
        this.noIngestOverride = path != null;
        if (path == null) {
            path = getBasePath();
        }
        this.basePath = path.normalize();
        this.config = StorageConfigUtil.getCreateStorageConfig(Config.class, c->c.version==1&&c.sectionStorageConfig!=null, ()->DEFAULT_STORAGE_CONFIG, this.basePath);
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            var swr = SodiumWorldRenderer.instanceNullable();
            if (swr != null) {
                var rsm = ((AccessorSodiumWorldRenderer) swr).getRenderSectionManager();
                if (rsm != null) {
                    this.setNumThreads(Math.max(1, target - rsm.getBuilder().getTotalThreadCount()));
                    return;
                }
            }
        }
        this.setNumThreads(target);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, Minecraft.getInstance().getUser().getProfileId().toString().replace(':','-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return (!this.noIngestOverride) && VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        //Free the render resources cache since the entire instance is freed
        RenderResourceReuse.clearResources();
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            basePath = iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else {
            String serverKey = getServerStorageKey();
            if (serverKey == null) {
                Logger.error("Server info unavailable, using UNKNOWN");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                basePath = basePath.resolve(serverKey);
            }
        }
        return basePath.toAbsolutePath();
    }

    private static String getServerStorageKey() {
        if (Minecraft.getInstance().isConnectedToRealms()) {
            return "realms";
        }

        String capturedServerKey = ClientSessionEvents.getCapturedServerStorageKey();
        if (capturedServerKey != null) {
            Logger.info("Using captured server data: " + ClientSessionEvents.getCapturedServerAddress());
            return capturedServerKey;
        }

        var directConnection = Minecraft.getInstance().getConnection();
        if (directConnection != null && directConnection.getServerData() != null) {
            Logger.info("Using connection server data: " + directConnection.getServerData().ip);
            return directConnection.getServerData().ip.replace(":", "_");
        }

        var gameMode = Minecraft.getInstance().gameMode;
        if (gameMode != null && gameMode.connection != null && gameMode.connection.getServerData() != null) {
            Logger.info("Using game mode server data: " + gameMode.connection.getServerData().ip);
            return gameMode.connection.getServerData().ip.replace(":", "_");
        }

        var currentServer = Minecraft.getInstance().getCurrentServer();
        if (currentServer != null) {
            Logger.info("Using current server data: " + currentServer.ip);
            return currentServer.ip.replace(":", "_");
        }

        return null;
    }
}
