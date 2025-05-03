package me.iceice666

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Configuration class for TpManager
 */
data class Config(
    // Teleport request expiration time in seconds
    val requestExpirationTimeSeconds: Int = 120,
    
    // Teleport cooldown in seconds 
    val teleportCooldownSeconds: Int = 30,
    
    // Whether to enable destination safety check
    val enableSafetyCheck: Boolean = true
) {
    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private val CONFIG_FILE: File = FabricLoader.getInstance().configDir.resolve("tp-manager.json").toFile()
        
        // Default configuration instance
        private val DEFAULT_CONFIG = Config()
        
        // Current loaded configuration
        private var currentConfig: Config = DEFAULT_CONFIG
        
        /**
         * Loads the configuration from disk
         * @return The loaded configuration
         */
        fun load(): Config {
            try {
                // Create default config if file doesn't exist
                if (!CONFIG_FILE.exists()) {
                    save(DEFAULT_CONFIG)
                    logger.info("Created default TpManager configuration file")
                    currentConfig = DEFAULT_CONFIG
                    return DEFAULT_CONFIG
                }
                
                // Read and parse the config file
                val json = String(Files.readAllBytes(CONFIG_FILE.toPath()))
                val config = GSON.fromJson(json, Config::class.java)
                
                if (config == null) {
                    logger.error("Failed to parse TpManager configuration, using defaults")
                    currentConfig = DEFAULT_CONFIG
                    return DEFAULT_CONFIG
                }
                
                logger.info("Loaded TpManager configuration")
                currentConfig = config
                return config
            } catch (e: IOException) {
                logger.error("Failed to read TpManager configuration file", e)
            } catch (e: JsonSyntaxException) {
                logger.error("Invalid syntax in TpManager configuration file", e)
            }
            
            // Return default config on error
            currentConfig = DEFAULT_CONFIG
            return DEFAULT_CONFIG
        }
        
        /**
         * Saves the configuration to disk
         * @param config The configuration to save
         */
        fun save(config: Config) {
            try {
                // Create parent directories if they don't exist
                if (!CONFIG_FILE.parentFile.exists()) {
                    CONFIG_FILE.parentFile.mkdirs()
                }
                
                // Write the config to file
                val json = GSON.toJson(config)
                Files.write(CONFIG_FILE.toPath(), json.toByteArray())
                currentConfig = config
            } catch (e: IOException) {
                logger.error("Failed to write TpManager configuration file", e)
            }
        }
        
        /**
         * Gets the current configuration
         * @return The current configuration
         */
        fun get(): Config {
            return currentConfig
        }
        
        /**
         * Reloads the configuration from disk
         */
        fun reload() {
            load()
            logger.info("Reloaded TpManager configuration")
        }
    }
    
    /**
     * Gets the request expiration time in milliseconds
     */
    fun getRequestExpirationTimeMillis(): Long {
        return TimeUnit.SECONDS.toMillis(requestExpirationTimeSeconds.toLong())
    }
    
    /**
     * Gets the teleport cooldown in milliseconds
     */
    fun getTeleportCooldownMillis(): Long {
        return TimeUnit.SECONDS.toMillis(teleportCooldownSeconds.toLong())
    }
}