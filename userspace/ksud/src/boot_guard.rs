//! 启动失败自动回滚（bootloop 保护）。
//!
//! 模块的 `post-fs-data.sh` 在开机很早就跑了，那时 `keystore2`、`system_server` 都还没起来。
//! 模块只要在这段时间里把关键服务搞崩，设备就停在开机动画、永远到不了 `sys.boot_completed`，
//! 只能刷回原 boot 才能救——也就是大家说的「刷错一个模块就变砖」。
//!
//! 这里给普通模块补一条 Magisk 那样的回滚：
//!   * 每次进入 `post-fs-data`，把 `boot_guard/attempts` 加一；
//!   * 正常到桌面（`boot_completed`）就清零；
//!   * 连续 2 次没等到 `boot_completed`，就禁用最近安装的那个模块；不知道是谁就禁用全部
//!     普通模块。下一次开机这些模块的脚本不再执行，系统能正常进桌面。
//!
//! 计数刻意放在 `post-fs-data`（而不是内核侧）：只有「ksud 已经起来、模块脚本马上要跑」
//! 才算一次尝试，用户平时正常重启不会累积，不会误伤。

use log::{info, warn};
use std::fs;
use std::path::{Path, PathBuf};

use crate::defs;

/// 连续多少次没完成启动就回滚。2 表示：模块把开机卡死一次，强制重启一次就能进系统。
const LIMIT: u32 = 2;

fn dir() -> PathBuf {
    Path::new(defs::WORKING_DIR).join("boot_guard")
}

fn attempts_path() -> PathBuf {
    dir().join("attempts")
}

fn last_module_path() -> PathBuf {
    dir().join("last_module")
}

fn log_path() -> PathBuf {
    dir().join("events.log")
}

fn read_u32(path: &Path) -> u32 {
    fs::read_to_string(path)
        .ok()
        .and_then(|s| s.trim().parse::<u32>().ok())
        .unwrap_or(0)
}

/// 直接覆盖写、不 fsync：只要求下一次开机读到新值。真断电丢一次计数，最多少保护一轮，
/// 不会让系统更糟。
fn write_str(path: &Path, data: &str) {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).ok();
    }
    if let Err(e) = fs::write(path, data) {
        warn!("boot_guard: write {} failed: {e}", path.display());
    }
}

fn append_log(msg: &str) {
    use std::io::Write;
    if let Some(parent) = log_path().parent() {
        fs::create_dir_all(parent).ok();
    }
    if let Ok(mut f) = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_path())
    {
        let _ = writeln!(f, "{msg}");
    }
    info!("boot_guard: {msg}");
}

/// 安装 / 更新模块成功后记一笔：以后要回滚就优先禁用它。
/// 顺手清掉上一轮计数——新装了东西，理应重新给它一次机会。
pub fn remember_install(module_id: &str) {
    write_str(&last_module_path(), module_id);
    fs::remove_file(attempts_path()).ok();
    append_log(&format!("remember-install {module_id}"));
}

/// 真正到达桌面（`boot_completed`）：把计数清零，本次开机不算失败。
pub fn on_boot_complete() {
    fs::remove_file(attempts_path()).ok();
}

/// 在 `post-fs-data` 里、执行模块脚本之前调用。
///
/// 返回 `true` 表示这次已触发保护（模块已经被标记禁用，调用方照常往下走即可：
/// 被禁用的模块本来就不会再执行脚本）。
#[must_use]
pub fn on_boot_start() -> bool {
    fs::create_dir_all(dir()).ok();

    let n = read_u32(&attempts_path()).saturating_add(1);
    write_str(&attempts_path(), &n.to_string());

    if n < LIMIT {
        return false;
    }

    // 到这里说明上一次开机没等到 boot_completed
    let last = fs::read_to_string(last_module_path())
        .ok()
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty() && s.len() <= 128);

    let mut disabled: Vec<String> = Vec::new();

    if let Some(id) = last {
        // 只认 modules/ 下真实存在的普通模块目录：
        //   * symlink 是 metamodule（框架本身），禁掉它解决不了问题，直接跳过；
        //   * 校验 id，防止状态文件被改后去动别的路径。
        let path = Path::new(defs::MODULE_DIR).join(&id);
        if path.is_dir() && !path.is_symlink() && crate::module::validate_module_id(&id).is_ok() {
            match crate::module::disable_module(&id) {
                Ok(()) => disabled.push(id),
                Err(e) => append_log(&format!("disable {id} failed: {e}")),
            }
        }
    }

    if disabled.is_empty() {
        // 没有可信的「最近安装模块」记录时不搞全禁。
        // boot_completed 的回调万一某次没跑到，计数会残留；这时如果无脑禁全部，
        // 用户正常在用的模块会一起被停掉，比原来的问题还严重。宁可这次放过。
        fs::remove_file(attempts_path()).ok();
        append_log(&format!(
            "bootloop-protect after {n} attempts, but no last_module; skipped"
        ));
        return true;
    }

    // 这一轮已经处理过，重新开始观察
    fs::remove_file(attempts_path()).ok();
    append_log(&format!(
        "bootloop-protect after {n} attempts, disabled=[{}]",
        disabled.join(",")
    ));
    true
}
