use std::path::PathBuf;

use tracing::{error, info};

use crate::audio::{list_input_devices, DeviceSwitcher};

/// Spawn the system tray icon on a dedicated OS thread.
/// If a DeviceSwitcher is provided, selecting a device switches live.
/// Otherwise (e.g. test-tone mode), the device menu is shown but disabled.
pub fn spawn_tray(
    current_device: Option<String>,
    switcher: Option<DeviceSwitcher>,
    url: String,
    log_path: PathBuf,
) {
    std::thread::spawn(move || {
        if let Err(e) = run_tray(current_device, switcher, url, log_path) {
            error!("System tray error: {}", e);
        }
    });
}

fn run_tray(
    mut current_device: Option<String>,
    switcher: Option<DeviceSwitcher>,
    url: String,
    log_path: PathBuf,
) -> anyhow::Result<()> {
    use muda::{Menu, MenuEvent, MenuItem, PredefinedMenuItem, Submenu};
    use tray_icon::TrayIconBuilder;

    #[cfg(target_os = "linux")]
    gtk::init().map_err(|e| anyhow::anyhow!("Failed to initialize GTK: {}", e))?;

    let menu = Menu::new();

    // Build device submenu
    let devices_submenu = Submenu::new("Audio Input", true);
    let devices = list_input_devices();

    let device_items = build_device_menu(&devices_submenu, &devices, &current_device)?;

    menu.append(&devices_submenu)?;
    menu.append(&PredefinedMenuItem::separator())?;

    let qr_item = MenuItem::new("Show QR Code", true, None);
    menu.append(&qr_item)?;

    let copy_url_item = MenuItem::new("Copy URL", true, None);
    menu.append(&copy_url_item)?;

    let log_item = MenuItem::new("Show Log", true, None);
    menu.append(&log_item)?;

    menu.append(&PredefinedMenuItem::separator())?;

    let quit_item = MenuItem::new("Quit", true, None);
    menu.append(&quit_item)?;

    let icon = create_icon();

    let _tray = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("WHCanRC Assisted Listening")
        .with_icon(icon)
        .build()?;

    info!("System tray icon created");

    let menu_rx = MenuEvent::receiver();

    loop {
        #[cfg(target_os = "windows")]
        {
            use windows::Win32::UI::WindowsAndMessaging::{
                DispatchMessageW, PeekMessageW, TranslateMessage, MSG, PM_REMOVE,
            };
            unsafe {
                let mut msg = MSG::default();
                while PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).into() {
                    let _ = TranslateMessage(&msg);
                    DispatchMessageW(&msg);
                }
            }
        }

        #[cfg(target_os = "linux")]
        while gtk::events_pending() {
            gtk::main_iteration_do(false);
        }

        if let Ok(event) = menu_rx.try_recv() {
            if event.id() == quit_item.id() {
                info!("Quit requested from tray");
                std::process::exit(0);
            }

            if event.id() == qr_item.id() {
                show_qr_code(&url);
            }

            if event.id() == copy_url_item.id() {
                copy_to_clipboard(&url);
            }

            if event.id() == log_item.id() {
                show_log(&log_path);
            }

            for (item, device_name) in &device_items {
                if event.id() == item.id() {
                    if let Some(ref switcher) = switcher {
                        info!("Switching to device: {}", device_name);
                        switcher.switch_device(device_name.clone());
                        current_device = Some(device_name.clone());

                        // Update menu labels to reflect new selection
                        let devices = list_input_devices();
                        update_device_labels(&device_items, &devices, &current_device);
                    }
                }
            }
        }

        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}

fn build_device_menu(
    submenu: &muda::Submenu,
    devices: &[(String, bool)],
    current_device: &Option<String>,
) -> anyhow::Result<Vec<(muda::MenuItem, String)>> {
    let mut items = Vec::new();
    for (name, is_default) in devices {
        let is_active = match current_device {
            Some(ref selected) => selected == name,
            None => *is_default,
        };
        let label = format_device_label(name, is_active, *is_default);
        let item = muda::MenuItem::new(label, true, None);
        submenu.append(&item)?;
        items.push((item, name.clone()));
    }
    Ok(items)
}

fn update_device_labels(
    items: &[(muda::MenuItem, String)],
    devices: &[(String, bool)],
    current_device: &Option<String>,
) {
    for (item, name) in items {
        let is_default = devices.iter().any(|(n, d)| n == name && *d);
        let is_active = match current_device {
            Some(ref selected) => selected == name,
            None => is_default,
        };
        item.set_text(format_device_label(name, is_active, is_default));
    }
}

fn format_device_label(name: &str, is_active: bool, is_default: bool) -> String {
    if is_active {
        format!("* {}", name)
    } else if is_default {
        format!("  {} (default)", name)
    } else {
        format!("  {}", name)
    }
}

fn show_qr_code(url: &str) {
    use qrcode::QrCode;

    let qr = match QrCode::new(url.as_bytes()) {
        Ok(qr) => qr,
        Err(e) => {
            error!("Failed to generate QR code: {}", e);
            return;
        }
    };

    let svg = qr
        .render::<qrcode::render::svg::Color>()
        .quiet_zone(true)
        .min_dimensions(200, 200)
        .build();

    let html = format!(
        r#"<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Connect — WHCanRC</title>
<style>
  body {{ display:flex; flex-direction:column; align-items:center; justify-content:center;
         height:100vh; margin:0; font-family:system-ui,sans-serif; background:#f5f5f5; }}
  a {{ font-size:1.2em; margin-top:1em; color:#2563eb; }}
</style></head>
<body>{svg}<a href="{url}">{url}</a></body></html>"#,
        svg = svg,
        url = url
    );

    let path = std::env::temp_dir().join("whcanrc_qr.html");
    if let Err(e) = std::fs::write(&path, html) {
        error!("Failed to write QR code file: {}", e);
        return;
    }

    info!("Opening QR code in browser");
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("cmd")
            .args(["/C", "start", "", &path.to_string_lossy()])
            .spawn();
    }
    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("open").arg(&path).spawn();
    }
    #[cfg(target_os = "linux")]
    {
        let _ = std::process::Command::new("xdg-open").arg(&path).spawn();
    }
}

fn copy_to_clipboard(text: &str) {
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("cmd")
            .args(["/C", &format!("echo|set /p={}|clip", text)])
            .spawn();
    }
    #[cfg(target_os = "macos")]
    {
        use std::io::Write;
        if let Ok(mut child) = std::process::Command::new("pbcopy")
            .stdin(std::process::Stdio::piped())
            .spawn()
        {
            if let Some(ref mut stdin) = child.stdin {
                let _ = stdin.write_all(text.as_bytes());
            }
        }
    }
    #[cfg(target_os = "linux")]
    {
        use std::io::Write;
        let (cmd, args): (&str, &[&str]) = if std::env::var("WAYLAND_DISPLAY").is_ok() {
            ("wl-copy", &[])
        } else {
            ("xclip", &["-selection", "clipboard"])
        };
        if let Ok(mut child) = std::process::Command::new(cmd)
            .args(args)
            .stdin(std::process::Stdio::piped())
            .spawn()
        {
            if let Some(ref mut stdin) = child.stdin {
                let _ = stdin.write_all(text.as_bytes());
            }
        }
    }
    info!("URL copied to clipboard");
}

fn show_log(log_path: &std::path::Path) {
    let path = log_path.to_string_lossy();
    info!("Opening log viewer: {}", path);

    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("cmd")
            .args([
                "/C",
                "start",
                "WHCanRC Log",
                "powershell",
                "-Command",
                &format!("Get-Content '{}' -Wait -Tail 100", path),
            ])
            .spawn();
    }

    #[cfg(target_os = "linux")]
    {
        // Try common terminal emulators in preference order
        let terminals: &[(&str, &[&str])] = &[
            ("x-terminal-emulator", &["-e"]),
            ("xdg-terminal-exec", &[]),
            ("gnome-terminal", &["--"]),
            ("konsole", &["-e"]),
            ("xfce4-terminal", &["-e"]),
            ("xterm", &["-e"]),
        ];

        for (term, args) in terminals {
            let mut cmd = std::process::Command::new(term);
            cmd.args(*args);
            cmd.args(["tail", "-n", "100", "-f", &path]);
            if cmd.spawn().is_ok() {
                return;
            }
        }
        error!("No terminal emulator found — install one or run: tail -f {}", path);
    }

    #[cfg(target_os = "macos")]
    {
        let script = format!(
            "tell application \"Terminal\" to do script \"tail -n 100 -f '{}'\"",
            path
        );
        let _ = std::process::Command::new("osascript")
            .args(["-e", &script])
            .spawn();
    }
}

fn create_icon() -> tray_icon::Icon {
    static ICON_PNG: &[u8] = include_bytes!("../icon.png");

    let decoder = png::Decoder::new(std::io::Cursor::new(ICON_PNG));
    let mut reader = decoder.read_info().expect("Failed to read icon PNG");
    let mut buf = vec![0u8; reader.output_buffer_size().expect("Failed to get icon buffer size")];
    let info = reader.next_frame(&mut buf).expect("Failed to decode icon PNG");
    buf.truncate(info.buffer_size());

    tray_icon::Icon::from_rgba(buf, info.width, info.height).expect("Failed to create tray icon")
}
