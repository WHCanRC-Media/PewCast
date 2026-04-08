use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

use tracing::{debug, info};

#[allow(dead_code)]
pub struct Client {
    pub id: String,
    pub addr: SocketAddr,
    pub joined_at: Instant,
    pub last_seen: Instant,
}

pub struct ClientRegistry {
    clients: RwLock<HashMap<String, Client>>,
}

impl Default for ClientRegistry {
    fn default() -> Self {
        Self::new()
    }
}

impl ClientRegistry {
    pub fn new() -> Self {
        Self {
            clients: RwLock::new(HashMap::new()),
        }
    }

    /// Register a new client, returning its assigned ID.
    pub fn join(&self, addr: SocketAddr) -> String {
        let id = format!("{:016x}", rand_id());
        let now = Instant::now();
        let client = Client {
            id: id.clone(),
            addr,
            joined_at: now,
            last_seen: now,
        };
        info!("UDP client joined: {} ({})", id, addr);
        self.clients.write().unwrap().insert(id.clone(), client);
        id
    }

    /// Update last-seen timestamp. Returns false if client not found.
    pub fn heartbeat(&self, client_id: &str) -> bool {
        if let Some(client) = self.clients.write().unwrap().get_mut(client_id) {
            client.last_seen = Instant::now();
            true
        } else {
            false
        }
    }

    /// Remove a client.
    pub fn leave(&self, client_id: &str) {
        if let Some(client) = self.clients.write().unwrap().remove(client_id) {
            info!("UDP client left: {} ({})", client_id, client.addr);
        }
    }

    /// Snapshot of all active client addresses for the UDP sender.
    pub fn get_addrs(&self) -> Vec<SocketAddr> {
        self.clients
            .read()
            .unwrap()
            .values()
            .map(|c| c.addr)
            .collect()
    }

    /// Number of connected clients.
    pub fn len(&self) -> usize {
        self.clients.read().unwrap().len()
    }

    #[allow(dead_code)]
    pub fn is_empty(&self) -> bool {
        self.clients.read().unwrap().is_empty()
    }

    /// Remove clients that haven't sent a heartbeat within `timeout`.
    pub fn reap_stale(&self, timeout: Duration) {
        let now = Instant::now();
        let mut clients = self.clients.write().unwrap();
        clients.retain(|id, client| {
            let alive = now.duration_since(client.last_seen) < timeout;
            if !alive {
                info!(
                    "Reaping stale UDP client: {} ({}) — no heartbeat for {:?}",
                    id, client.addr, timeout
                );
            }
            alive
        });
    }

    /// Spawn a background thread that reaps stale clients periodically.
    pub fn spawn_reaper(registry: Arc<ClientRegistry>, timeout: Duration) {
        std::thread::Builder::new()
            .name("client-reaper".into())
            .spawn(move || loop {
                std::thread::sleep(Duration::from_secs(5));
                let before = registry.len();
                registry.reap_stale(timeout);
                let after = registry.len();
                if before != after {
                    debug!("Reaper: {} -> {} UDP clients", before, after);
                }
            })
            .expect("Failed to spawn client reaper thread");
    }
}

/// Simple random ID using thread-local RNG via std.
fn rand_id() -> u64 {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    let s = RandomState::new();
    let mut h = s.build_hasher();
    h.write_usize(0);
    h.finish()
}
