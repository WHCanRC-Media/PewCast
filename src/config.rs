use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use tracing::{info, warn};

#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct Config {
    pub port: u16,
    pub log_level: String,
    pub audio_sample_rate: u32,
    pub audio_channels: u16,
    /// Opus frame duration in milliseconds. Valid values: 5, 10, 20, 40, 60.
    /// Lower values reduce latency but increase overhead.
    pub opus_frame_ms: u64,
    /// Use WASAPI exclusive mode on Windows for lower capture latency.
    /// Falls back to shared mode if exclusive mode fails.
    pub wasapi_exclusive: bool,
    /// Timeout in seconds before reaping UDP clients with no heartbeat.
    pub heartbeat_timeout_secs: u64,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: 8080,
            log_level: "info".to_string(),
            audio_sample_rate: 48000,
            audio_channels: 1,
            opus_frame_ms: 5,
            wasapi_exclusive: false,
            heartbeat_timeout_secs: 15,
        }
    }
}

impl Config {
    /// Load config from file (if it exists) then apply environment variable overrides.
    pub fn load() -> anyhow::Result<Self> {
        let mut config = if Path::new("config.toml").exists() {
            let contents = std::fs::read_to_string("config.toml")?;
            info!("Loaded config from config.toml");
            toml::from_str(&contents)?
        } else {
            info!("No config.toml found, using defaults");
            Config::default()
        };

        // Apply environment variable overrides (PEWCAST_ prefix)
        if let Ok(val) = std::env::var("PEWCAST_PORT") {
            config.port = val.parse()?;
        }
        if let Ok(val) = std::env::var("PEWCAST_LOG_LEVEL") {
            config.log_level = val;
        }
        if let Ok(val) = std::env::var("PEWCAST_AUDIO_SAMPLE_RATE") {
            config.audio_sample_rate = val.parse()?;
        }
        if let Ok(val) = std::env::var("PEWCAST_AUDIO_CHANNELS") {
            config.audio_channels = val.parse()?;
        }
        if let Ok(val) = std::env::var("PEWCAST_OPUS_FRAME_MS") {
            config.opus_frame_ms = val.parse()?;
        }
        if let Ok(val) = std::env::var("PEWCAST_WASAPI_EXCLUSIVE") {
            config.wasapi_exclusive = val == "1" || val.eq_ignore_ascii_case("true");
        }
        if let Ok(val) = std::env::var("PEWCAST_HEARTBEAT_TIMEOUT_SECS") {
            config.heartbeat_timeout_secs = val.parse()?;
        }

        Ok(config)
    }
}

/// User preferences persisted across launches (device selection, exclusive mode).
/// Stored in `%APPDATA%/pewcast/prefs.toml` on Windows,
/// `~/.config/pewcast/prefs.toml` on Linux/macOS.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct UserPrefs {
    pub device: Option<String>,
    pub wasapi_exclusive: Option<bool>,
}

impl UserPrefs {
    fn prefs_path() -> Option<PathBuf> {
        dirs::config_dir().map(|d| d.join("pewcast").join("prefs.toml"))
    }

    /// Load saved preferences. Returns defaults if the file doesn't exist or is corrupt.
    pub fn load() -> Self {
        let Some(path) = Self::prefs_path() else {
            return Self::default();
        };
        match std::fs::read_to_string(&path) {
            Ok(contents) => match toml::from_str(&contents) {
                Ok(prefs) => {
                    info!("Loaded user preferences from {}", path.display());
                    prefs
                }
                Err(e) => {
                    warn!("Failed to parse {}: {}, using defaults", path.display(), e);
                    Self::default()
                }
            },
            Err(_) => Self::default(),
        }
    }

    /// Save current preferences to disk.
    pub fn save(&self) {
        let Some(path) = Self::prefs_path() else {
            return;
        };
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match toml::to_string_pretty(self) {
            Ok(contents) => {
                if let Err(e) = std::fs::write(&path, contents) {
                    warn!("Failed to save preferences to {}: {}", path.display(), e);
                }
            }
            Err(e) => warn!("Failed to serialize preferences: {}", e),
        }
    }
}
