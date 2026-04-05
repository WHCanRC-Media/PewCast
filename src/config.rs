use serde::Deserialize;
use std::path::Path;
use tracing::info;

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

        // Apply environment variable overrides (WHCANRC_ prefix)
        if let Ok(val) = std::env::var("WHCANRC_PORT") {
            config.port = val.parse()?;
        }
        if let Ok(val) = std::env::var("WHCANRC_LOG_LEVEL") {
            config.log_level = val;
        }
        if let Ok(val) = std::env::var("WHCANRC_AUDIO_SAMPLE_RATE") {
            config.audio_sample_rate = val.parse()?;
        }
        if let Ok(val) = std::env::var("WHCANRC_AUDIO_CHANNELS") {
            config.audio_channels = val.parse()?;
        }
        if let Ok(val) = std::env::var("WHCANRC_OPUS_FRAME_MS") {
            config.opus_frame_ms = val.parse()?;
        }
        if let Ok(val) = std::env::var("WHCANRC_WASAPI_EXCLUSIVE") {
            config.wasapi_exclusive = val == "1" || val.eq_ignore_ascii_case("true");
        }

        Ok(config)
    }
}
