use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, UdpSocket};

use mdns_sd::{ServiceDaemon, ServiceInfo};
use tracing::{error, info};

use crate::config::Config;

const SERVICE_TYPE: &str = "_whcanrc._tcp.local.";

/// Determine the local IPv4 address used for LAN traffic.
fn local_ipv4() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("1.1.1.1:80").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(ip) => Some(ip),
        _ => None,
    }
}

/// Determine the local IPv6 address used for LAN traffic.
fn local_ipv6() -> Option<Ipv6Addr> {
    let socket = UdpSocket::bind("[::]:0").ok()?;
    socket.connect("[2606:4700:4700::1111]:80").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V6(ip) => Some(ip),
        _ => None,
    }
}

/// Register the server as an mDNS service so native clients can discover it.
/// Returns the ServiceDaemon handle (keeps advertisement alive while held).
pub fn advertise(config: &Config) -> Option<ServiceDaemon> {
    let mdns = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => {
            error!("Failed to create mDNS daemon: {}", e);
            return None;
        }
    };

    let hostname = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "whcanrc-server".to_string());

    let mut addrs: Vec<IpAddr> = Vec::new();
    if let Some(ip4) = local_ipv4() {
        addrs.push(IpAddr::V4(ip4));
    }
    if let Some(ip6) = local_ipv6() {
        addrs.push(IpAddr::V6(ip6));
    }
    if addrs.is_empty() {
        error!("Failed to determine any local IP for mDNS advertisement");
        return None;
    }

    let properties = [
        ("sample_rate", config.audio_sample_rate.to_string()),
        ("channels", config.audio_channels.to_string()),
    ];

    let addr_strings: Vec<String> = addrs.iter().map(|a| a.to_string()).collect();
    let addr_strs: Vec<&str> = addr_strings.iter().map(|s| s.as_str()).collect();

    let service_info = match ServiceInfo::new(
        SERVICE_TYPE,
        &hostname,
        &format!("{}.local.", hostname),
        addr_strs.as_slice(),
        config.port,
        &properties[..],
    ) {
        Ok(info) => info,
        Err(e) => {
            error!("Failed to create mDNS service info: {}", e);
            return None;
        }
    };

    match mdns.register(service_info) {
        Ok(()) => {
            info!(
                "mDNS: advertising {} on port {} as '{}' ({:?})",
                SERVICE_TYPE, config.port, hostname, addrs
            );
        }
        Err(e) => {
            error!("Failed to register mDNS service: {}", e);
            return None;
        }
    }

    Some(mdns)
}
