use std::process::Command;

fn git(args: &[&str]) -> Option<String> {
    let output = Command::new("git").args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let s = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if s.is_empty() {
        None
    } else {
        Some(s)
    }
}

fn main() {
    // Stamp the binary with the git identity of the Android app so the /status
    // endpoint can tell apps when a newer APK is available.
    let sha = git(&["log", "-1", "--format=%H", "--", "android"])
        .unwrap_or_else(|| "unknown".to_string());
    let count = git(&["rev-list", "--count", "HEAD", "--", "android"])
        .and_then(|s| s.parse::<u32>().ok())
        .unwrap_or(0);

    println!("cargo:rustc-env=APK_GIT_SHA={}", sha);
    println!("cargo:rustc-env=APK_COMMIT_COUNT={}", count);
    println!("cargo:rerun-if-changed=android");
    println!("cargo:rerun-if-changed=.git/HEAD");
    println!("cargo:rerun-if-changed=.git/refs/heads");

    #[cfg(target_os = "windows")]
    {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("icon.ico");
        res.compile().expect("Failed to compile Windows resources");
    }
}
