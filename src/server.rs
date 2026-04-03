use std::sync::Arc;

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse};
use axum::routing::{get, post};
use axum::Json;
use axum::Router;
use serde::{Deserialize, Serialize};
use tokio::sync::{Mutex, RwLock};
use tower_http::cors::CorsLayer;
use tracing::{error, info};
use webrtc::ice_transport::ice_candidate::RTCIceCandidateInit;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;
use webrtc::peer_connection::RTCPeerConnection;

use crate::config::Config;
use crate::webrtc::PeerManager;
use crate::webtransport::WebTransportState;

/// Shared application state accessible from all route handlers.
pub struct AppState {
    pub peer_manager: PeerManager,
    /// Most recently created peer connection (for ICE candidate trickle).
    /// In a production system you'd map session IDs to peers, but for LAN
    /// use with sequential connections this is sufficient.
    pub last_peer: Mutex<Option<Arc<RTCPeerConnection>>>,
    /// Port for WebTransport server (QUIC/HTTP3).
    pub webtransport_port: u16,
    /// Shared state containing WebTransport certificate hash.
    pub webtransport_state: Arc<RwLock<Option<WebTransportState>>>,
    /// Server configuration (for diagnostics endpoint).
    pub config: Config,
}

#[derive(Deserialize)]
#[allow(dead_code)]
pub struct OfferRequest {
    pub sdp: String,
    #[serde(rename = "type")]
    pub sdp_type: String,
}

#[derive(Serialize)]
pub struct AnswerResponse {
    pub sdp: String,
    #[serde(rename = "type")]
    pub sdp_type: String,
}

#[derive(Deserialize)]
pub struct IceCandidateRequest {
    pub candidate: String,
    #[serde(rename = "sdpMid")]
    pub sdp_mid: Option<String>,
    #[serde(rename = "sdpMLineIndex")]
    pub sdp_mline_index: Option<u16>,
}

/// Build the axum Router with all routes.
pub fn build_router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/", get(index_handler))
        .route("/offer", post(offer_handler))
        .route("/ice-candidate", post(ice_candidate_handler))
        .route("/status", get(status_handler))
        .route("/latency_test", get(latency_test_handler))
        .route("/transport-info", get(transport_info_handler))
        .route("/latency_test/report", post(latency_report_handler))
        .route("/latency_test/server-info", get(server_info_handler))
        .layer(CorsLayer::permissive())
        .with_state(state)
}

/// Serve the listener HTML page.
async fn index_handler() -> impl IntoResponse {
    Html(include_str!("../static/index.html"))
}

/// Serve the latency test page.
async fn latency_test_handler() -> impl IntoResponse {
    Html(include_str!("../static/latency_test.html"))
}

/// Handle SDP offer from browser, return SDP answer.
async fn offer_handler(
    State(state): State<Arc<AppState>>,
    Json(offer_req): Json<OfferRequest>,
) -> Result<Json<AnswerResponse>, (StatusCode, String)> {
    let offer = RTCSessionDescription::offer(offer_req.sdp).map_err(|e| {
        error!("Invalid SDP offer: {}", e);
        (StatusCode::BAD_REQUEST, format!("Invalid SDP offer: {}", e))
    })?;

    let (answer, peer) = state.peer_manager.handle_offer(offer).await.map_err(|e| {
        error!("Failed to handle offer: {}", e);
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to create answer: {}", e),
        )
    })?;

    // Store the peer for ICE candidate trickle
    *state.last_peer.lock().await = Some(peer);

    Ok(Json(AnswerResponse {
        sdp: answer.sdp,
        sdp_type: "answer".to_string(),
    }))
}

/// Handle trickle ICE candidate from browser.
async fn ice_candidate_handler(
    State(state): State<Arc<AppState>>,
    Json(candidate_req): Json<IceCandidateRequest>,
) -> Result<StatusCode, (StatusCode, String)> {
    let peer_lock = state.last_peer.lock().await;
    let peer = peer_lock.as_ref().ok_or((
        StatusCode::BAD_REQUEST,
        "No active peer connection".to_string(),
    ))?;

    let candidate = RTCIceCandidateInit {
        candidate: candidate_req.candidate,
        sdp_mid: candidate_req.sdp_mid,
        sdp_mline_index: candidate_req.sdp_mline_index,
        ..Default::default()
    };

    PeerManager::add_ice_candidate(peer, candidate)
        .await
        .map_err(|e| {
            error!("Failed to add ICE candidate: {}", e);
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("Failed to add ICE candidate: {}", e),
            )
        })?;

    Ok(StatusCode::NO_CONTENT)
}

/// Simple status endpoint.
#[derive(Serialize, Deserialize)]
struct StatusResponse {
    status: String,
    active_peers: usize,
}

async fn status_handler(State(state): State<Arc<AppState>>) -> Json<StatusResponse> {
    Json(StatusResponse {
        status: "running".to_string(),
        active_peers: state.peer_manager.peer_count().await,
    })
}

/// Transport info endpoint - tells the client about WebTransport availability.
#[derive(Serialize)]
struct TransportInfoResponse {
    webtransport_port: u16,
    /// Base64-encoded SHA-256 hash of the WebTransport certificate.
    /// Required for browsers to connect to self-signed certs.
    cert_hash: Option<String>,
}

async fn transport_info_handler(State(state): State<Arc<AppState>>) -> Json<TransportInfoResponse> {
    let wt_state = state.webtransport_state.read().await;
    let cert_hash = wt_state.as_ref().map(|s| s.cert_hash.clone());

    Json(TransportInfoResponse {
        webtransport_port: state.webtransport_port,
        cert_hash,
    })
}

/// Client-reported latency diagnostics.
#[derive(Deserialize, Debug)]
struct LatencyReport {
    /// "webtransport" or "webrtc"
    transport: String,
    /// User agent string
    user_agent: String,
    /// AudioContext.outputLatency in ms (playout latency)
    output_latency_ms: Option<f64>,
    /// AudioContext.baseLatency in ms (processing latency)
    base_latency_ms: Option<f64>,
    /// AudioContext.sampleRate
    audio_ctx_sample_rate: Option<u32>,
    /// Chirp round-trip measurements in ms
    chirp_rtt: Option<Vec<f64>>,
    /// Audio session RTT measurements in ms (WebTransport ping probes)
    audio_rtt: Option<Vec<f64>>,
    /// WebRTC jitter buffer delay in ms (from getStats)
    jitter_buffer_delay_ms: Option<f64>,
    /// WebRTC jitter buffer target delay in ms
    jitter_buffer_target_ms: Option<f64>,
    /// WebRTC jitter buffer minimum delay in ms
    jitter_buffer_min_delay_ms: Option<f64>,
    /// WebRTC packets received
    packets_received: Option<u64>,
    /// WebRTC packets lost
    packets_lost: Option<u64>,
    /// WebRTC jitter in seconds (from getStats)
    jitter: Option<f64>,
}

async fn latency_report_handler(Json(report): Json<LatencyReport>) -> StatusCode {
    // Log structured summary for easy reading in server console / log file
    info!("=== Latency Test Report ===");
    info!("  Transport: {}", report.transport);
    info!("  User-Agent: {}", report.user_agent);

    if let Some(v) = report.output_latency_ms {
        info!("  AudioContext.outputLatency: {:.1}ms (playout)", v);
    }
    if let Some(v) = report.base_latency_ms {
        info!("  AudioContext.baseLatency: {:.1}ms (processing)", v);
    }
    if let Some(sr) = report.audio_ctx_sample_rate {
        info!("  AudioContext.sampleRate: {}Hz", sr);
    }

    if let Some(ref rtt) = report.chirp_rtt {
        if !rtt.is_empty() {
            let min = rtt.iter().cloned().fold(f64::INFINITY, f64::min);
            let max = rtt.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            let avg = rtt.iter().sum::<f64>() / rtt.len() as f64;
            let sorted = {
                let mut s = rtt.clone();
                s.sort_by(|a, b| a.partial_cmp(b).unwrap());
                s
            };
            let p50 = sorted[sorted.len() / 2];
            info!(
                "  Chirp RTT (n={}): min={:.0}ms avg={:.0}ms p50={:.0}ms max={:.0}ms",
                rtt.len(),
                min,
                avg,
                p50,
                max
            );
            // Estimate one-way: chirp RTT includes speaker→mic→server→client
            // Subtract playout to approximate server pipeline latency
            if let Some(playout) = report.output_latency_ms {
                let one_way_est = p50 - playout;
                info!(
                    "  Estimated one-way audio (p50 chirp - playout): {:.0}ms",
                    one_way_est
                );
            }
        }
    }

    if let Some(ref rtt) = report.audio_rtt {
        if !rtt.is_empty() {
            let min = rtt.iter().cloned().fold(f64::INFINITY, f64::min);
            let max = rtt.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            let avg = rtt.iter().sum::<f64>() / rtt.len() as f64;
            let sorted = {
                let mut s = rtt.clone();
                s.sort_by(|a, b| a.partial_cmp(b).unwrap());
                s
            };
            let p50 = sorted[sorted.len() / 2];
            let p95 = sorted[(sorted.len() as f64 * 0.95) as usize];
            info!(
                "  Audio RTT (n={}): min={:.1}ms avg={:.1}ms p50={:.1}ms p95={:.1}ms max={:.1}ms",
                rtt.len(),
                min,
                avg,
                p50,
                p95,
                max
            );
            info!("  Estimated one-way network: {:.1}ms", p50 / 2.0);
        }
    }

    if report.jitter_buffer_delay_ms.is_some()
        || report.packets_received.is_some()
        || report.jitter.is_some()
    {
        info!("  WebRTC stats:");
        if let Some(v) = report.jitter_buffer_delay_ms {
            info!("    Jitter buffer delay: {:.1}ms", v);
        }
        if let Some(v) = report.jitter_buffer_target_ms {
            info!("    Jitter buffer target: {:.1}ms", v);
        }
        if let Some(v) = report.jitter_buffer_min_delay_ms {
            info!("    Jitter buffer min delay: {:.1}ms", v);
        }
        if let Some(v) = report.jitter {
            info!("    Jitter: {:.3}s ({:.1}ms)", v, v * 1000.0);
        }
        if let Some(v) = report.packets_received {
            info!("    Packets received: {}", v);
        }
        if let Some(v) = report.packets_lost {
            info!("    Packets lost: {}", v);
        }
    }

    info!("=== End Report ===");
    StatusCode::NO_CONTENT
}

/// Server-side info for latency budget validation.
#[derive(Serialize)]
struct ServerInfoResponse {
    /// "exclusive" or "shared"
    capture_mode: String,
    /// Configured sample rate in Hz
    sample_rate: u32,
    /// Number of audio channels
    channels: u16,
    /// Opus frame duration in ms
    opus_frame_ms: u64,
    /// Capture buffer size in samples (estimated from config)
    capture_buffer_samples: u32,
    /// Capture buffer duration in ms
    capture_buffer_ms: f64,
    /// Active WebRTC peers
    active_peers: usize,
}

async fn server_info_handler(State(state): State<Arc<AppState>>) -> Json<ServerInfoResponse> {
    let cfg = &state.config;

    // Estimate capture buffer: exclusive=144, shared=240 (typical)
    let capture_buf = if cfg.wasapi_exclusive { 144u32 } else { 240 };
    let capture_ms = capture_buf as f64 / cfg.audio_sample_rate as f64 * 1000.0;

    Json(ServerInfoResponse {
        capture_mode: if cfg.wasapi_exclusive {
            "exclusive".to_string()
        } else {
            "shared".to_string()
        },
        sample_rate: cfg.audio_sample_rate,
        channels: cfg.audio_channels,
        opus_frame_ms: cfg.opus_frame_ms,
        capture_buffer_samples: capture_buf,
        capture_buffer_ms: capture_ms,
        active_peers: state.peer_manager.peer_count().await,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::Request;
    use tower::ServiceExt;

    fn test_app_state() -> Arc<AppState> {
        Arc::new(AppState {
            peer_manager: PeerManager::new().unwrap(),
            last_peer: Mutex::new(None),
            webtransport_port: 8081,
            webtransport_state: Arc::new(RwLock::new(None)),
            config: Config::default(),
        })
    }

    #[tokio::test]
    async fn test_index_returns_html() {
        let state = test_app_state();
        let app = build_router(state);

        let response = app
            .oneshot(Request::builder().uri("/").body(Body::empty()).unwrap())
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        let html = String::from_utf8(body.to_vec()).unwrap();
        assert!(html.contains("<!DOCTYPE html>"));
        assert!(html.contains("Listen"));
    }

    #[tokio::test]
    async fn test_status_endpoint() {
        let state = test_app_state();
        let app = build_router(state);

        let response = app
            .oneshot(
                Request::builder()
                    .uri("/status")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        let status: StatusResponse = serde_json::from_slice(&body).unwrap();
        assert_eq!(status.status, "running");
        assert_eq!(status.active_peers, 0);
    }

    #[tokio::test]
    async fn test_offer_with_invalid_sdp() {
        let state = test_app_state();
        let app = build_router(state);

        let response = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/offer")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"sdp": "invalid", "type": "offer"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();

        // Should return an error (either 400 or 500 depending on how webrtc-rs handles it)
        assert!(response.status().is_client_error() || response.status().is_server_error());
    }

    #[tokio::test]
    async fn test_ice_candidate_without_peer() {
        let state = test_app_state();
        let app = build_router(state);

        let response = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/ice-candidate")
                    .header("content-type", "application/json")
                    .body(Body::from(
                        r#"{"candidate": "candidate:1 1 UDP 2122252543 192.168.1.1 12345 typ host", "sdpMid": "0", "sdpMLineIndex": 0}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn test_offer_with_malformed_json() {
        let state = test_app_state();
        let app = build_router(state);

        let response = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/offer")
                    .header("content-type", "application/json")
                    .body(Body::from("not json"))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert!(response.status().is_client_error());
    }
}
