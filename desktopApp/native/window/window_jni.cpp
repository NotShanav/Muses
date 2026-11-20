#define _WIN32_WINNT 0x0A00
#include <jni.h>

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <audioclient.h>
#include <dwmapi.h>
#include <functiondiscoverykeys_devpkey.h>
#include <mmdeviceapi.h>
#include <propvarutil.h>
#include <windowsx.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cwchar>
#include <cwctype>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

HWND g_window = nullptr;
WNDPROC g_original_proc = nullptr;

// Posted from Java to start a caption drag on the thread that owns the window; see frame_proc.
constexpr UINT WM_BITCHORD_DRAG = WM_APP + 0x42;
// SC_MOVE through the caption: the same move loop a real title bar starts.
constexpr WPARAM SC_DRAGMOVE = SC_MOVE | HTCAPTION;

struct FindContext {
    const wchar_t* title;
    HWND found;
};

BOOL CALLBACK find_own_window(HWND window, LPARAM param) {
    auto* context = reinterpret_cast<FindContext*>(param);
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    if (pid != GetCurrentProcessId() || !IsWindowVisible(window)) return TRUE;

    wchar_t buffer[256] = {};
    GetWindowTextW(window, buffer, 255);
    if (wcscmp(buffer, context->title) != 0) return TRUE;
    context->found = window;
    return FALSE;
}

HWND find_window(JNIEnv* env, jstring title) {
    if (g_window != nullptr && IsWindow(g_window)) return g_window;
    const jchar* chars = env->GetStringChars(title, nullptr);
    const jsize length = env->GetStringLength(title);
    std::wstring wanted(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(title, chars);
    FindContext context{wanted.c_str(), nullptr};
    EnumWindows(find_own_window, reinterpret_cast<LPARAM>(&context));
    return context.found;
}

int resize_border(HWND window, bool horizontal) {
    const UINT dpi = GetDpiForWindow(window);
    const int frame = GetSystemMetricsForDpi(horizontal ? SM_CXFRAME : SM_CYFRAME, dpi);
    const int padding = GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
    return frame + padding;
}


// ---- Display and posture change tracking ------------------------------------------------------
//
// Rotating a 2-in-1, changing the scaling or plugging a monitor all arrive here as window
// messages. They are passed on to AWT first (it resizes the peer for WM_DPICHANGED itself) and
// then counted. Kotlin polls the counters rather than being called back: a callback would run
// Java on the window thread in the middle of a message, and the poll costs one atomic read.
std::atomic<long long> g_display_generation{0};
std::atomic<long long> g_posture_generation{0};
// 360 T1: the last WM_SIZE (counter, type, window rect) for desktop.log; polled from Kotlin.
std::atomic<int> g_size_counter{0};
std::atomic<int> g_size_type{0};
std::atomic<int> g_size_rect[4] = {};
// How many times the maximize fix ran for the current maximize; capped so it can never loop.
std::atomic<int> g_fix_attempts{0};
// Posted to ourselves after a maximize that did not end up on the work area; see frame_proc.
constexpr UINT WM_BITCHORD_FIXMAX = WM_APP + 0x43;
// The backdrop last asked for, so a refresh can put the same frame margins back.
std::atomic<int> g_backdrop_kind{0};

#ifndef SM_CONVERTIBLESLATEMODE
#define SM_CONVERTIBLESLATEMODE 0x2003
#endif
#ifndef SM_SYSTEMDOCKED
#define SM_SYSTEMDOCKED 0x2004
#endif
#ifndef SM_TABLETPC
#define SM_TABLETPC 86
#endif
#ifndef SM_DIGITIZER
#define SM_DIGITIZER 94
#endif

/**
 * Whether the pixel at [point] belongs to a resize border rather than to the content.
 *
 * A side only resizes while the window's edge on that side is clear of the monitor's work area.
 * An edge lying on the work area (snapped, maximized, or simply dragged against the screen) cannot
 * be pulled outward, and the pixels there are the ones Compose draws its scrollbars, buttons and
 * mini player in: they are client pixels, never a border and never click-through.
 */
struct EdgeSides {
    bool left, top, right, bottom;
};

EdgeSides resizable_sides(const RECT& bounds, const RECT& work) {
    constexpr LONG slack = 1;
    return EdgeSides{
        bounds.left > work.left + slack,
        bounds.top > work.top + slack,
        bounds.right < work.right - slack,
        bounds.bottom < work.bottom - slack,
    };
}

bool work_area_of(HWND window, RECT* work, RECT* monitor_rect) {
    MONITORINFO monitor{sizeof(MONITORINFO)};
    const HMONITOR handle = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
    if (handle == nullptr || GetMonitorInfoW(handle, &monitor) == FALSE) return false;
    if (work != nullptr) *work = monitor.rcWork;
    if (monitor_rect != nullptr) *monitor_rect = monitor.rcMonitor;
    return true;
}

LRESULT call_original(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    WNDPROC original = g_original_proc;
    return original != nullptr
        ? CallWindowProcW(original, window, message, wparam, lparam)
        : DefWindowProcW(window, message, wparam, lparam);
}

LRESULT CALLBACK frame_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_NCCALCSIZE && wparam == TRUE) {
        // Keep the native overlapped-window styles but give the full surface to Compose. DWM still
        // owns the outer frame, shadow and transitions; only the standard caption is removed.
        if (IsZoomed(window)) {
            // A frameless client normally expands to the maximized window rectangle, which can
            // extend a few pixels into a taskbar docked at the top of the monitor. Use the monitor
            // work area for the client portion so the first row is never hidden behind the taskbar.
            MONITORINFO monitor{sizeof(MONITORINFO)};
            const HMONITOR handle = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
            if (handle != nullptr && GetMonitorInfoW(handle, &monitor) != FALSE) {
                auto* sizes = reinterpret_cast<NCCALCSIZE_PARAMS*>(lparam);
                sizes->rgrc[0] = monitor.rcWork;
            }
        }
        return 0;
    }

    if (message == WM_NCHITTEST) {
        POINT point{GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam)};
        RECT bounds{};
        if (GetWindowRect(window, &bounds) != FALSE && PtInRect(&bounds, point) != FALSE) {
            // Maximized, or filling the work area: every pixel is content. The client is the work
            // area (see WM_NCCALCSIZE), so there is no resize strip to hand out.
            if (IsZoomed(window)) return HTCLIENT;
            RECT work{};
            const bool known = work_area_of(window, &work, nullptr);
            const EdgeSides sides = known ? resizable_sides(bounds, work) : EdgeSides{true, true, true, true};
            const int border_x = resize_border(window, true);
            const int border_y = resize_border(window, false);
            const bool left = sides.left && point.x < bounds.left + border_x;
            const bool right = sides.right && point.x >= bounds.right - border_x;
            const bool top = sides.top && point.y < bounds.top + border_y;
            const bool bottom = sides.bottom && point.y >= bounds.bottom - border_y;
            if (top && left) return HTTOPLEFT;
            if (top && right) return HTTOPRIGHT;
            if (bottom && left) return HTBOTTOMLEFT;
            if (bottom && right) return HTBOTTOMRIGHT;
            if (left) return HTLEFT;
            if (right) return HTRIGHT;
            if (top) return HTTOP;
            if (bottom) return HTBOTTOM;
            // Inside the window and not on a border: ours, whatever alpha is painted there.
            return HTCLIENT;
        }
    }

    if (message == WM_GETMINMAXINFO) {
        // AWT fills this from the window's minimumSize, which Kotlin sets in device pixels. A
        // rotated monitor can be narrower than that minimum; Windows then lets the window grow
        // past the screen and the swap chain and the native size disagree. Never ask for more
        // than the monitor's work area, and maximize to exactly that area.
        const LRESULT handled = call_original(window, message, wparam, lparam);
        auto* info = reinterpret_cast<MINMAXINFO*>(lparam);
        RECT work{}, monitor_rect{};
        if (info != nullptr && work_area_of(window, &work, &monitor_rect)) {
            const LONG width = work.right - work.left;
            const LONG height = work.bottom - work.top;
            if (info->ptMinTrackSize.x > width) info->ptMinTrackSize.x = width;
            if (info->ptMinTrackSize.y > height) info->ptMinTrackSize.y = height;
            info->ptMaxPosition.x = work.left - monitor_rect.left;
            info->ptMaxPosition.y = work.top - monitor_rect.top;
            info->ptMaxSize.x = width;
            info->ptMaxSize.y = height;
            if (info->ptMaxTrackSize.x < width) info->ptMaxTrackSize.x = width;
            if (info->ptMaxTrackSize.y < height) info->ptMaxTrackSize.y = height;
        }
        return handled;
    }

    if (message == WM_SIZE) {
        const LRESULT handled = call_original(window, message, wparam, lparam);
        RECT rect{};
        if (GetWindowRect(window, &rect) != FALSE) {
            g_size_type.store(static_cast<int>(wparam));
            g_size_rect[0].store(rect.left);
            g_size_rect[1].store(rect.top);
            g_size_rect[2].store(rect.right);
            g_size_rect[3].store(rect.bottom);
            g_size_counter.fetch_add(1);
            // Snap-to-top and maximize must fill the work area of this monitor. If they stopped
            // short (a stale rect, a rotated monitor), say so once and let the posted message fix it.
            if (wparam == SIZE_MAXIMIZED) {
                PostMessageW(window, WM_BITCHORD_FIXMAX, 0, 0);
            } else {
                g_fix_attempts.store(0);
            }
        }
        return handled;
    }

    if (message == WM_BITCHORD_FIXMAX) {
        if (IsZoomed(window)) {
            RECT work{}, rect{};
            if (work_area_of(window, &work, nullptr) && GetWindowRect(window, &rect) != FALSE) {
                constexpr LONG tolerance = 2;
                const bool off = std::labs(rect.left - work.left) > tolerance ||
                    std::labs(rect.top - work.top) > tolerance ||
                    std::labs(rect.right - work.right) > tolerance ||
                    std::labs(rect.bottom - work.bottom) > tolerance;
                if (off && g_fix_attempts.fetch_add(1) < 2) {
                    SetWindowPos(
                        window, nullptr, work.left, work.top, work.right - work.left,
                        work.bottom - work.top, SWP_NOZORDER | SWP_NOACTIVATE);
                }
            }
        }
        return 0;
    }

    if (message == WM_DISPLAYCHANGE || message == WM_DPICHANGED || message == WM_SETTINGCHANGE) {
        const LRESULT handled = call_original(window, message, wparam, lparam);
        if (message == WM_SETTINGCHANGE) {
            // A work area change is a display change; the named settings (ConvertibleSlateMode,
            // UserInteractionMode) arrive with wparam 0 and a string in lparam that is not read
            // here: the posture is asked for by value, so one more poll is all it costs.
            if (wparam == SPI_SETWORKAREA) {
                g_display_generation.fetch_add(1);
            } else if (wparam == 0) {
                g_posture_generation.fetch_add(1);
            }
        } else {
            g_display_generation.fetch_add(1);
        }
        return handled;
    }

    if (message == WM_BITCHORD_DRAG) {
        // Windows moves the window itself from here, as it does for a system caption: DWM slides
        // the composed surface, so nothing is exposed to be filled with the class brush (the white
        // band an AWT setLocation per mouse event left along the edges), and Aero Snap and
        // drag-to-restore come with it. The press that asked for this may already be over; a move
        // loop started without the button down would follow the cursor until the next click.
        const int primary = GetSystemMetrics(SM_SWAPBUTTON) ? VK_RBUTTON : VK_LBUTTON;
        if ((GetAsyncKeyState(primary) & 0x8000) == 0) return 0;
        // AWT captured the mouse on the press; the move loop needs it back.
        ReleaseCapture();
        DefWindowProcW(window, WM_SYSCOMMAND, SC_DRAGMOVE, 0);
        // The loop ate the release. AWT, and Compose behind it, still hold the press that started
        // the drag and would take the next one for part of it: a double click on the caption
        // never became one, and the first click anywhere after a drag went missing. Hand the
        // release back where the pointer now is.
        POINT cursor{};
        if (GetCursorPos(&cursor) != FALSE && ScreenToClient(window, &cursor) != FALSE) {
            PostMessageW(window, WM_LBUTTONUP, 0, MAKELPARAM(cursor.x, cursor.y));
        }
        return 0;
    }

    WNDPROC original = g_original_proc;
    if (message == WM_NCDESTROY) {
        g_window = nullptr;
        g_original_proc = nullptr;
    }
    return original != nullptr
        ? CallWindowProcW(original, window, message, wparam, lparam)
        : DefWindowProcW(window, message, wparam, lparam);
}

bool install_frame(HWND window) {
    if (window == nullptr) return false;
    if (window == g_window && g_original_proc != nullptr) return true;

    SetLastError(0);
    auto previous = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(frame_proc)));
    if (previous == nullptr && GetLastError() != 0) return false;

    g_window = window;
    g_original_proc = previous;

    LONG_PTR style = GetWindowLongPtrW(window, GWL_STYLE);
    style &= ~static_cast<LONG_PTR>(WS_POPUP);
    style |= WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_THICKFRAME |
        WS_MINIMIZEBOX | WS_MAXIMIZEBOX;
    SetWindowLongPtrW(window, GWL_STYLE, style);

    // Ask DWM to own the non-client rendering and Windows 11 corner policy. Unlike an AWT shape,
    // these do not clip the surface and are automatically disabled when maximized or snapped.
    const DWMNCRENDERINGPOLICY policy = DWMNCRP_ENABLED;
    DwmSetWindowAttribute(
        window, DWMWA_NCRENDERING_POLICY, &policy, sizeof(policy));
#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif
    const int rounded = 2;  // DWMWCP_ROUND; kept numeric so older SDKs can still build the bridge.
    DwmSetWindowAttribute(
        window, static_cast<DWMWINDOWATTRIBUTE>(DWMWA_WINDOW_CORNER_PREFERENCE),
        &rounded, sizeof(rounded));

    MARGINS margins{1, 1, 1, 1};
    DwmExtendFrameIntoClientArea(window, &margins);
    SetWindowPos(
        window, nullptr, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    return true;
}

// Windows 11 22H2's system backdrop attribute and its values; numeric so older SDKs still build.
constexpr DWORD BACKDROP_ATTRIBUTE = 38;  // DWMWA_SYSTEMBACKDROP_TYPE
constexpr DWORD DARK_MODE_ATTRIBUTE = 20;  // DWMWA_USE_IMMERSIVE_DARK_MODE
constexpr int BACKDROP_NONE = 1;  // DWMSBT_NONE

/**
 * Puts DWM's own material behind the window: 2 Mica, 3 Acrylic, anything else none. The frame is
 * extended over the whole client so the material reaches everywhere Compose leaves transparent;
 * without a material it goes back to the one-pixel margin the frame needs for its shadow.
 *
 * Dark mode is what makes the material a dark tint rather than a light one. False when the system
 * has no backdrop attribute (before Windows 11 22H2), and the caller keeps the window opaque.
 */
bool set_backdrop(HWND window, int kind) {
    if (window == nullptr || !IsWindow(window)) return false;
    const BOOL dark = TRUE;
    DwmSetWindowAttribute(
        window, static_cast<DWMWINDOWATTRIBUTE>(DARK_MODE_ATTRIBUTE), &dark, sizeof(dark));
    const bool material = kind == 2 || kind == 3;
    g_backdrop_kind = material ? kind : 0;
    const int type = material ? kind : BACKDROP_NONE;
    const HRESULT applied = DwmSetWindowAttribute(
        window, static_cast<DWMWINDOWATTRIBUTE>(BACKDROP_ATTRIBUTE), &type, sizeof(type));
    if (FAILED(applied)) return false;
    MARGINS margins = material ? MARGINS{-1, -1, -1, -1} : MARGINS{1, 1, 1, 1};
    DwmExtendFrameIntoClientArea(window, &margins);
    return true;
}

bool send_system_command(UINT command) {
    HWND window = g_window;
    return window != nullptr && IsWindow(window) &&
        PostMessageW(window, WM_SYSCOMMAND, command, 0) != 0;
}

// ---- Shared-mode Windows audio --------------------------------------------
//
// One render stream, owned by the playback thread through the JNI calls below. What Kotlin asks
// for is kept (g_audio_wanted: an endpoint id, a legacy device name, or blank for the system
// default) separately from the endpoint actually open, because the two drift apart: Windows'
// default moves when the user picks another output in the flyout, and a chosen DAC can be
// unplugged and plugged back in. An IMMNotificationClient flags those moments, and the next write
// moves the stream to wherever the request now resolves — keeping the played-frame count, so the
// position Kotlin derives from it does not jump.
//
// AUDIO FIX — locking rules (read before editing):
//   * g_audio_mutex guards the stream (client, render client, counters). Nothing may sleep or
//     spin while holding it; a wait is always `unlock(); Sleep(); lock();` or a plain return.
//   * g_enum_mutex guards only the creation of the shared enumerator. Lock order is always
//     g_audio_mutex, then g_enum_mutex. list_devices() takes g_enum_mutex alone.
//   * g_info_mutex guards the two strings the UI-facing getters copy (device name, last error).
//     It is a leaf lock: nothing else is called while it is held.
//   * The notification callbacks (COM threads) take no lock at all: atomics only.
//   * A stream that Windows invalidated (device change, spatial-audio / effect switch, Bluetooth
//     profile change, audio service restart) is never reused: it is marked, released and rebuilt
//     with a short back-off, even when the endpoint id did not change.

// ---- AUDIO FIX: diagnostic trace -------------------------------------------
//
// A fixed ring of short records, written to a text file by a small watchdog thread (never by the
// thread that records, so no file I/O happens under g_audio_mutex). Records carry thread ids,
// event codes, a hash of an endpoint id and HRESULTs; never a device name or a file path.

enum TraceCode : int {
    EV_NONE = 0,
    EV_DEV_ADDED,
    EV_DEV_REMOVED,
    EV_DEV_STATE,
    EV_DEV_DEFAULT,
    EV_DEV_PROPERTY,
    EV_REROUTE,
    EV_REBUILD_BEGIN,
    EV_REBUILD_END,
    EV_INVALIDATED,
    EV_LOCK_WAIT,
    EV_LOCK_HELD,
    EV_RETRY,
    EV_STALL,
    EV_DRAIN_TIMEOUT,
    EV_OPEN,
    EV_NOTIFY,
    EV_ERROR,
    EV_STA_REFUSED,
    EV_JNI_FAIL,
    EV_RECOVERED,
    EV_COUNT
};

const char* const kTraceNames[EV_COUNT] = {
    "none", "dev-added", "dev-removed", "dev-state", "dev-default", "dev-property", "reroute",
    "rebuild-begin", "rebuild-end", "invalidated", "lock-wait", "lock-held", "retry", "stall",
    "drain-timeout", "open", "notify", "error", "sta-refused", "jni-fail", "recovered"};

enum AudioEntry : int {
    EN_NONE = 0,
    EN_OPEN,
    EN_WRITE,
    EN_FRAMES,
    EN_PAUSE,
    EN_RESUME,
    EN_FLUSH,
    EN_DRAIN,
    EN_CLOSE,
    EN_COUNT
};

const char* const kEntryNames[EN_COUNT] = {
    "none", "open", "write", "framesPlayed", "pause", "resume", "flush", "drain", "close"};

struct TraceRecord {
    uint32_t ms;
    uint32_t thread;
    int32_t code;
    int32_t a;
    int32_t b;
};

constexpr uint32_t kTraceSize = 256;
TraceRecord g_trace[kTraceSize] = {};
std::atomic<uint32_t> g_trace_next{0};
std::atomic<bool> g_error_state{false};
std::atomic<bool> g_flush_requested{false};
std::atomic<ULONGLONG> g_trace_flushed_at{0};
std::atomic<bool> g_watchdog_started{false};
std::mutex g_trace_mutex;  // guards g_trace_path and the file write; never held with another lock
std::wstring g_trace_path;

// Who holds g_audio_mutex and since when; read by the watchdog without taking the lock.
std::atomic<int> g_holder_entry{0};
std::atomic<ULONGLONG> g_holder_since{0};
std::atomic<int> g_waiters[EN_COUNT];

int32_t endpoint_hash(const std::wstring& id) {
    uint32_t hash = 2166136261u;
    for (const wchar_t c : id) {
        hash ^= static_cast<uint32_t>(c);
        hash *= 16777619u;
    }
    return static_cast<int32_t>(hash);
}

/** Lock-free: a torn record under a race is possible and harmless. */
void trace_add(int code, int32_t a = 0, int32_t b = 0) {
    const uint32_t slot = g_trace_next.fetch_add(1) % kTraceSize;
    TraceRecord& record = g_trace[slot];
    record.ms = static_cast<uint32_t>(GetTickCount64());
    record.thread = static_cast<uint32_t>(GetCurrentThreadId());
    record.code = code;
    record.a = a;
    record.b = b;
}

/** Records an error and asks the watchdog thread to write the file soon. Takes no lock. */
void trace_error(int code, int32_t a = 0, int32_t b = 0) {
    trace_add(code, a, b);
    g_error_state = true;
    g_flush_requested = true;
}

void trace_flush() {
    std::lock_guard<std::mutex> guard(g_trace_mutex);
    if (g_trace_path.empty()) return;
    TraceRecord copy[kTraceSize];
    for (uint32_t index = 0; index < kTraceSize; ++index) copy[index] = g_trace[index];
    const uint32_t next = g_trace_next.load();
    const ULONGLONG now = GetTickCount64();

    std::string text;
    text.reserve(16 * 1024);
    char line[200];
    const int holder = g_holder_entry.load();
    const ULONGLONG since = g_holder_since.load();
    snprintf(
        line, sizeof(line),
        "Muses audio trace. now=%u ms errorState=%d holder=%s heldMs=%u\n",
        static_cast<unsigned>(now), g_error_state.load() ? 1 : 0,
        (holder > 0 && holder < EN_COUNT) ? kEntryNames[holder] : "none",
        static_cast<unsigned>(since != 0 && now >= since ? now - since : 0));
    text += line;
    for (int entry = 1; entry < EN_COUNT; ++entry) {
        const int waiting = g_waiters[entry].load();
        if (waiting > 0) {
            snprintf(line, sizeof(line), "waiting: %s x%d\n", kEntryNames[entry], waiting);
            text += line;
        }
    }
    const uint32_t count = std::min<uint32_t>(next, kTraceSize);
    const uint32_t first = next >= kTraceSize ? next % kTraceSize : 0;
    for (uint32_t index = 0; index < count; ++index) {
        const TraceRecord& record = copy[(first + index) % kTraceSize];
        const char* name = (record.code >= 0 && record.code < EV_COUNT) ? kTraceNames[record.code] : "?";
        snprintf(
            line, sizeof(line), "%10u t%-6u %-14s a=%d b=0x%08X\n",
            static_cast<unsigned>(record.ms), static_cast<unsigned>(record.thread), name,
            static_cast<int>(record.a), static_cast<unsigned>(record.b));
        text += line;
    }

    HANDLE file = CreateFileW(
        g_trace_path.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
        CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteFile(file, text.data(), static_cast<DWORD>(text.size()), &written, nullptr);
        CloseHandle(file);
    }
    g_trace_flushed_at = GetTickCount64();
}

/**
 * Wakes every 100 ms. It reports a g_audio_mutex that stays held for more than 300 ms (which
 * entry point, how long) without ever taking that lock itself, so it still works in a real
 * freeze, and it writes the trace on request and every 2 s while an error state persists.
 */
DWORD WINAPI trace_watchdog(LPVOID) {
    ULONGLONG held_logged_at = 0;
    for (;;) {
        Sleep(100);
        const ULONGLONG now = GetTickCount64();
        const ULONGLONG since = g_holder_since.load();
        if (since != 0 && now >= since && now - since >= 300 && now - held_logged_at >= 2000) {
            held_logged_at = now;
            trace_add(EV_LOCK_HELD, g_holder_entry.load(), static_cast<int32_t>(now - since));
            g_flush_requested = true;
        }
        const bool periodic = g_error_state.load() && now - g_trace_flushed_at.load() >= 2000;
        if (g_flush_requested.exchange(false) || periodic) trace_flush();
    }
    return 0;
}

void trace_set_path(const std::wstring& path) {
    {
        std::lock_guard<std::mutex> guard(g_trace_mutex);
        g_trace_path = path;
    }
    bool expected = false;
    if (g_watchdog_started.compare_exchange_strong(expected, true)) {
        HANDLE thread = CreateThread(nullptr, 0, trace_watchdog, nullptr, 0, nullptr);
        if (thread != nullptr) {
            CloseHandle(thread);
        } else {
            g_watchdog_started = false;
        }
    }
    g_flush_requested = true;
}

// ---- Strings the UI-facing getters copy ------------------------------------

std::mutex g_info_mutex;  // leaf lock
std::wstring g_audio_device_name;
std::wstring g_audio_error;

void set_error_text(const std::wstring& text) {
    std::lock_guard<std::mutex> guard(g_info_mutex);
    g_audio_error = text;
}

void set_device_name(const std::wstring& name) {
    std::lock_guard<std::mutex> guard(g_info_mutex);
    g_audio_device_name = name;
}

std::wstring copy_device_name() {
    std::lock_guard<std::mutex> guard(g_info_mutex);
    return g_audio_device_name;
}

std::wstring copy_error_text() {
    std::lock_guard<std::mutex> guard(g_info_mutex);
    return g_audio_error;
}

void set_audio_error(const wchar_t* operation, HRESULT result) {
    wchar_t code[32] = {};
    swprintf(code, 31, L"0x%08lX", static_cast<unsigned long>(result));
    std::wstring text = operation;
    text += L" failed (";
    text += code;
    text += L")";
    set_error_text(text);
    trace_error(EV_ERROR, 0, static_cast<int32_t>(result));
}

// ---- The stream ------------------------------------------------------------

std::mutex g_audio_mutex;  // the stream; see the locking rules above
std::mutex g_enum_mutex;   // creation of the enumerator only; lock order: g_audio_mutex, then this
IAudioClient* g_audio_client = nullptr;
IAudioRenderClient* g_audio_render = nullptr;
UINT32 g_audio_buffer_frames = 0;
// The format Kotlin opened with; kept across reroutes so a rebuilt stream takes the same samples.
int g_audio_rate = 0;
UINT32 g_audio_channels = 0;
UINT32 g_audio_bytes_per_sample = 0;
bool g_audio_float = false;
// Whether playback wants the stream running — independent of there being a client right now.
bool g_audio_started = false;
uint64_t g_audio_submitted_frames = 0;
uint64_t g_audio_played_offset = 0;
// AUDIO FIX: the last played-frame count computed while the client answered. A dead client cannot
// report one, and counting its queued frames as heard would make the position jump.
uint64_t g_last_played = 0;
std::atomic<uint64_t> g_played_cache{0};
std::wstring g_audio_wanted;
std::wstring g_audio_endpoint_id;

// Set from the notification threads; read by the playback thread on its next write.
std::atomic<bool> g_audio_reroute{false};
// AUDIO FIX: set when the stream is dead (device invalidated and the like). Forces a rebuild even
// when the endpoint id is unchanged, which is exactly the spatial-audio / Bluetooth-profile case.
std::atomic<bool> g_audio_invalidated{false};
// Consecutive failed or re-invalidated rebuilds, and the earliest time of the next attempt.
int g_rebuild_failures = 0;
ULONGLONG g_next_rebuild_ms = 0;

std::atomic<IMMDeviceEnumerator*> g_audio_enumerator{nullptr};
bool g_audio_watching = false;  // guarded by g_enum_mutex
JavaVM* g_vm = nullptr;

// AUDIO FIX: raw values so this builds whatever the MinGW headers define.
constexpr HRESULT kEDeviceInvalidated = static_cast<HRESULT>(0x88890004u);
constexpr HRESULT kENotInitialized = static_cast<HRESULT>(0x88890001u);
constexpr HRESULT kEUnsupportedFormat = static_cast<HRESULT>(0x88890008u);
constexpr HRESULT kEDeviceInUse = static_cast<HRESULT>(0x8889000Au);
constexpr HRESULT kEEndpointCreateFailed = static_cast<HRESULT>(0x8889000Fu);
constexpr HRESULT kEServiceNotRunning = static_cast<HRESULT>(0x88890010u);
constexpr HRESULT kEResourcesInvalidated = static_cast<HRESULT>(0x88890026u);
constexpr HRESULT kERpcDisconnected = static_cast<HRESULT>(0x80010108u);
constexpr HRESULT kERpcServerUnavailable = static_cast<HRESULT>(0x800706BAu);

/** HRESULTs that mean the stream cannot be used any more and has to be rebuilt. */
bool is_stream_dead(HRESULT status) {
    return status == kEDeviceInvalidated || status == kEServiceNotRunning ||
        status == kEResourcesInvalidated || status == kEDeviceInUse ||
        status == kEUnsupportedFormat || status == kENotInitialized ||
        status == kEEndpointCreateFailed || status == kERpcDisconnected ||
        status == kERpcServerUnavailable;
}

thread_local bool t_com_mta = false;

class ComScope {
public:
    ComScope() : result(CoInitializeEx(nullptr, COINIT_MULTITHREADED)), previous(t_com_mta) {
        t_com_mta = SUCCEEDED(result);
    }
    ~ComScope() {
        t_com_mta = previous;
        if (result == S_OK || result == S_FALSE) CoUninitialize();
    }
    ComScope(const ComScope&) = delete;
    ComScope& operator=(const ComScope&) = delete;
    bool ready() const { return SUCCEEDED(result) || result == RPC_E_CHANGED_MODE; }
    /** False on a thread that is already a single-threaded apartment (such as the AWT thread). */
    bool mta() const { return SUCCEEDED(result); }
    HRESULT result;

private:
    bool previous;
};

/**
 * Holds g_audio_mutex and tells the watchdog who has it. Waiters are counted, so a freeze shows
 * both the holder and the entry points queued behind it. Unlocking never does file I/O.
 */
class AudioLock {
public:
    explicit AudioLock(int entry) : entry_(entry) { lock(); }
    ~AudioLock() {
        if (owns_) unlock();
    }
    AudioLock(const AudioLock&) = delete;
    AudioLock& operator=(const AudioLock&) = delete;

    void lock() {
        g_waiters[entry_].fetch_add(1);
        const ULONGLONG begin = GetTickCount64();
        g_audio_mutex.lock();
        g_waiters[entry_].fetch_sub(1);
        owns_ = true;
        const ULONGLONG now = GetTickCount64();
        g_holder_entry = entry_;
        g_holder_since = now;
        if (now - begin > 300) trace_error(EV_LOCK_WAIT, entry_, static_cast<int32_t>(now - begin));
    }
    void unlock() {
        g_holder_since = 0;
        g_holder_entry = 0;
        owns_ = false;
        g_audio_mutex.unlock();
    }

private:
    int entry_;
    bool owns_ = false;
};

// ---- Telling Kotlin the device list changed --------------------------------
//
// The callback thread must stay cheap: it only raises a flag. One worker thread turns a burst of
// callbacks (an effect switch produces dozens) into a single call into Kotlin, 250 ms after the
// first one. The class and method are cached as a global reference and a method id; a Java
// exception is always cleared.

std::atomic<jclass> g_cb_class{nullptr};
std::atomic<jmethodID> g_cb_method{nullptr};
std::mutex g_cb_mutex;
std::atomic<bool> g_notify_pending{false};
std::mutex g_notify_start_mutex;
HANDLE g_notify_event = nullptr;

void cache_callback(JNIEnv* env) {
    if (env == nullptr || g_cb_method.load() != nullptr) return;
    std::lock_guard<std::mutex> guard(g_cb_mutex);
    if (g_cb_method.load() != nullptr) return;
    jclass local = env->FindClass("com/music/bitchord/desktop/DesktopWindowsAudio");
    if (local == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        trace_add(EV_JNI_FAIL, 1, 0);
        return;
    }
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (global == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        trace_add(EV_JNI_FAIL, 2, 0);
        return;
    }
    jmethodID method = env->GetStaticMethodID(global, "onDevicesChanged", "()V");
    if (method == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(global);
        trace_add(EV_JNI_FAIL, 3, 0);
        return;
    }
    g_cb_class = global;
    g_cb_method = method;
}

void deliver_devices_changed() {
    JavaVM* vm = g_vm;
    jclass type = g_cb_class.load();
    jmethodID method = g_cb_method.load();
    if (vm == nullptr || type == nullptr || method == nullptr) {
        trace_add(EV_JNI_FAIL, 4, 0);
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK || env == nullptr) {
            trace_add(EV_JNI_FAIL, 5, 0);
            return;
        }
        attached = true;
    }
    env->CallStaticVoidMethod(type, method);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) vm->DetachCurrentThread();
    trace_add(EV_NOTIFY, 0, 0);
}

DWORD WINAPI notifier_proc(LPVOID) {
    for (;;) {
        if (WaitForSingleObject(g_notify_event, INFINITE) != WAIT_OBJECT_0) return 0;
        Sleep(250);                 // let the burst settle
        g_notify_pending = false;   // anything from here on earns another round
        deliver_devices_changed();
    }
}

void request_devices_changed() {
    {
        std::lock_guard<std::mutex> guard(g_notify_start_mutex);
        if (g_notify_event == nullptr) {
            HANDLE event = CreateEventW(nullptr, FALSE, FALSE, nullptr);  // auto-reset
            if (event == nullptr) return;
            g_notify_event = event;  // the worker reads it as soon as it starts
            HANDLE thread = CreateThread(nullptr, 0, notifier_proc, nullptr, 0, nullptr);
            if (thread == nullptr) {
                g_notify_event = nullptr;
                CloseHandle(event);
                return;
            }
            CloseHandle(thread);
        }
    }
    if (!g_notify_pending.exchange(true)) SetEvent(g_notify_event);
}

/** Lives as long as the process; registered once with the shared enumerator, never unregistered. */
class EndpointWatcher final : public IMMNotificationClient {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void** out) override {
        if (out == nullptr) return E_POINTER;
        if (id == __uuidof(IUnknown) || id == __uuidof(IMMNotificationClient)) {
            *out = static_cast<IMMNotificationClient*>(this);
            return S_OK;
        }
        *out = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }

    HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow flow, ERole, LPCWSTR id) override {
        // Fires once per role; the reroute resolves the multimedia default and is a no-op when
        // the stream is already there, so reacting to every role costs nothing.
        if (flow == eRender) {
            g_audio_reroute = true;
            trace_add(EV_DEV_DEFAULT, id != nullptr ? endpoint_hash(id) : 0, 0);
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR id) override { return changed(EV_DEV_ADDED, id, 0); }
    HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR id) override { return changed(EV_DEV_REMOVED, id, 0); }
    HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR id, DWORD state) override {
        return changed(EV_DEV_STATE, id, static_cast<int32_t>(state));
    }
    HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR id, const PROPERTYKEY key) override {
        // Spatial-audio and effect switches show up here in bursts. Recorded, nothing else: the
        // stream tells us itself when it was invalidated.
        trace_add(EV_DEV_PROPERTY, id != nullptr ? endpoint_hash(id) : 0, static_cast<int32_t>(key.pid));
        return S_OK;
    }

private:
    /** Never throws into COM, takes no audio lock, and does no JNI work on this thread. */
    static HRESULT changed(int code, LPCWSTR id, int32_t detail) {
        try {
            g_audio_reroute = true;
            trace_add(code, id != nullptr ? endpoint_hash(id) : 0, detail);
            request_devices_changed();
        } catch (...) {
        }
        return S_OK;
    }
};

EndpointWatcher g_audio_watcher;

/**
 * The enumerator every audio call shares, created once and kept with the notification client
 * registered on it. CoIncrementMTAUsage keeps the multithreaded apartment — and so the enumerator
 * — alive between calls, which each enter and leave it. It is never released, so a pointer taken
 * from here stays valid for the life of the process, and the registered watcher (a static object)
 * outlives every callback.
 *
 * Takes only g_enum_mutex, and only for creation, so listing devices never waits on the stream.
 * Creation is refused on a single-threaded-apartment thread: an enumerator created there would
 * live in that apartment and deliver its notifications through that thread's message loop.
 */
IMMDeviceEnumerator* get_enumerator() {
    IMMDeviceEnumerator* ready = g_audio_enumerator.load();
    if (ready != nullptr) return ready;
    std::lock_guard<std::mutex> guard(g_enum_mutex);
    ready = g_audio_enumerator.load();
    if (ready != nullptr) return ready;
    if (!t_com_mta) {
        trace_error(EV_STA_REFUSED, 0, 0);
        set_error_text(L"Audio endpoint discovery needs a multithreaded COM thread");
        return nullptr;
    }
    static CO_MTA_USAGE_COOKIE mta = nullptr;
    if (mta == nullptr) CoIncrementMTAUsage(&mta);
    IMMDeviceEnumerator* created = nullptr;
    const HRESULT status = CoCreateInstance(
        __uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
        __uuidof(IMMDeviceEnumerator), reinterpret_cast<void**>(&created));
    if (FAILED(status) || created == nullptr) {
        set_audio_error(L"Audio endpoint discovery", status);
        return nullptr;
    }
    if (!g_audio_watching) {
        g_audio_watching = SUCCEEDED(created->RegisterEndpointNotificationCallback(&g_audio_watcher));
    }
    g_audio_enumerator.store(created);
    return created;
}

/** Drops the stream itself; the format, request and running state survive for a rebuild. */
void release_client_locked() {
    if (g_audio_client != nullptr) g_audio_client->Stop();
    if (g_audio_render != nullptr) {
        g_audio_render->Release();
        g_audio_render = nullptr;
    }
    if (g_audio_client != nullptr) {
        g_audio_client->Release();
        g_audio_client = nullptr;
    }
    g_audio_buffer_frames = 0;
    g_audio_endpoint_id.clear();
    set_device_name(L"");
}

void release_audio_locked() {
    release_client_locked();
    g_audio_rate = 0;
    g_audio_channels = 0;
    g_audio_bytes_per_sample = 0;
    g_audio_float = false;
    g_audio_started = false;
    g_audio_submitted_frames = 0;
    g_audio_played_offset = 0;
    g_last_played = 0;
    g_played_cache = 0;
    g_audio_wanted.clear();
    g_audio_reroute = false;
    g_audio_invalidated = false;
    g_rebuild_failures = 0;
    g_next_rebuild_ms = 0;
}

std::wstring property_string(IMMDevice* device, const PROPERTYKEY& key) {
    IPropertyStore* store = nullptr;
    if (FAILED(device->OpenPropertyStore(STGM_READ, &store)) || store == nullptr) return L"";
    PROPVARIANT value;
    PropVariantInit(&value);
    std::wstring result;
    if (SUCCEEDED(store->GetValue(key, &value)) && value.vt == VT_LPWSTR && value.pwszVal != nullptr) {
        result = value.pwszVal;
    }
    PropVariantClear(&value);
    store->Release();
    return result;
}

std::wstring endpoint_id(IMMDevice* device) {
    LPWSTR id = nullptr;
    std::wstring result;
    if (SUCCEEDED(device->GetId(&id)) && id != nullptr) {
        result = id;
        CoTaskMemFree(id);
    }
    return result;
}

std::wstring lower(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(), [](wchar_t c) {
        return static_cast<wchar_t>(towlower(c));
    });
    return value;
}

/** Endpoint ids look like `{0.0.0.00000000}.{guid}`; anything else is a name from before them. */
bool is_endpoint_id(const std::wstring& value) {
    return value.size() > 2 && value[0] == L'{' && value.find(L"}.{") != std::wstring::npos;
}

IMMDevice* default_device(IMMDeviceEnumerator* enumerator) {
    IMMDevice* device = nullptr;
    return SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, &device)) ? device : nullptr;
}

bool is_active(IMMDevice* device) {
    DWORD state = 0;
    return SUCCEEDED(device->GetState(&state)) && state == DEVICE_STATE_ACTIVE;
}

/**
 * A device stored by name before ids were used. Only an exact name counts: the old substring match
 * let every "Speakers (…)" request land on whichever endpoint described itself as "Speakers" first.
 */
IMMDevice* device_named(IMMDeviceEnumerator* enumerator, const std::wstring& wanted) {
    IMMDeviceCollection* devices = nullptr;
    if (FAILED(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &devices)) || devices == nullptr) {
        return nullptr;
    }
    const std::wstring needle = lower(wanted);
    UINT count = 0;
    devices->GetCount(&count);
    IMMDevice* match = nullptr;
    for (UINT index = 0; index < count && match == nullptr; ++index) {
        IMMDevice* candidate = nullptr;
        if (FAILED(devices->Item(index, &candidate)) || candidate == nullptr) continue;
        if (lower(property_string(candidate, PKEY_Device_FriendlyName)) == needle) {
            match = candidate;
        } else {
            candidate->Release();
        }
    }
    devices->Release();
    return match;
}

/**
 * Where [wanted] should play right now: that endpoint while it is plugged in, otherwise the
 * system default — headphones get unplugged, and that should not stop playback.
 */
IMMDevice* resolve_device_locked(const std::wstring& wanted) {
    IMMDeviceEnumerator* enumerator = get_enumerator();
    if (enumerator == nullptr) return nullptr;
    if (!wanted.empty()) {
        IMMDevice* device = nullptr;
        if (is_endpoint_id(wanted)) {
            if (FAILED(enumerator->GetDevice(wanted.c_str(), &device))) device = nullptr;
        } else {
            device = device_named(enumerator, wanted);
        }
        if (device != nullptr && is_active(device)) return device;
        if (device != nullptr) device->Release();
    }
    return default_device(enumerator);
}

/**
 * Builds the render stream on [device] in the stored format; starts it if playback is running.
 * S_OK on success, otherwise the HRESULT that failed. Can block for seconds on Bluetooth or while
 * an audio effect reconfigures, which is why nothing the UI thread calls ever takes g_audio_mutex.
 */
HRESULT start_client_locked(IMMDevice* device) {
    HRESULT status = device->Activate(
        __uuidof(IAudioClient), CLSCTX_ALL, nullptr,
        reinterpret_cast<void**>(&g_audio_client));
    if (FAILED(status) || g_audio_client == nullptr) {
        g_audio_client = nullptr;
        set_audio_error(L"Opening the audio endpoint", status);
        return FAILED(status) ? status : E_FAIL;
    }

    WAVEFORMATEX format{};
    format.wFormatTag = g_audio_float ? WAVE_FORMAT_IEEE_FLOAT : WAVE_FORMAT_PCM;
    format.nChannels = static_cast<WORD>(g_audio_channels);
    format.nSamplesPerSec = static_cast<DWORD>(g_audio_rate);
    format.wBitsPerSample = static_cast<WORD>(g_audio_bytes_per_sample * 8);
    format.nBlockAlign = static_cast<WORD>(g_audio_channels * g_audio_bytes_per_sample);
    format.nAvgBytesPerSec = format.nSamplesPerSec * format.nBlockAlign;
    format.cbSize = 0;

#ifndef AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
#define AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM 0x80000000
#endif
#ifndef AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY
#define AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY 0x08000000
#endif
    const DWORD flags = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
    // 200 ms keeps network/decoder jitter away from the device without making controls sluggish.
    status = g_audio_client->Initialize(
        AUDCLNT_SHAREMODE_SHARED, flags, 2'000'000, 0, &format, nullptr);
    if (FAILED(status)) {
        set_audio_error(L"Starting WASAPI shared mode", status);
        release_client_locked();
        return status;
    }
    status = g_audio_client->GetBufferSize(&g_audio_buffer_frames);
    if (SUCCEEDED(status)) {
        status = g_audio_client->GetService(
            __uuidof(IAudioRenderClient), reinterpret_cast<void**>(&g_audio_render));
    }
    if (SUCCEEDED(status) && g_audio_started) status = g_audio_client->Start();
    if (FAILED(status) || g_audio_render == nullptr) {
        set_audio_error(L"Starting the WASAPI render stream", status);
        release_client_locked();
        return FAILED(status) ? status : E_FAIL;
    }
    g_audio_endpoint_id = endpoint_id(device);
    set_device_name(property_string(device, PKEY_Device_FriendlyName));
    return S_OK;
}

/** Waits before the next rebuild: 50, 100, 200, then 250 ms. Never sleeps; only sets a time. */
void schedule_retry_locked() {
    ++g_rebuild_failures;
    const int step = std::min(g_rebuild_failures, 4);
    const ULONGLONG delay = std::min<ULONGLONG>(250, ULONGLONG{25} << step);
    g_next_rebuild_ms = GetTickCount64() + delay;
    trace_add(EV_RETRY, g_rebuild_failures, static_cast<int32_t>(delay));
}

/**
 * The stream is dead. Keep what was heard so far, drop the client, and force a rebuild (also when
 * the endpoint id turns out to be the same) after a short back-off. Safe to call any number of times.
 */
void mark_stream_dead_locked(HRESULT status, int where) {
    trace_error(EV_INVALIDATED, where, static_cast<int32_t>(status));
    g_audio_played_offset = std::max(g_audio_played_offset, g_last_played);
    g_audio_submitted_frames = 0;
    g_last_played = g_audio_played_offset;
    release_client_locked();
    g_audio_invalidated = true;
    set_audio_error(L"The audio stream was invalidated", status);
    schedule_retry_locked();
}

uint64_t played_frames_locked() {
    if (g_audio_client == nullptr) return g_audio_played_offset;
    UINT32 padding = 0;
    const HRESULT status = g_audio_client->GetCurrentPadding(&padding);
    if (FAILED(status)) {
        // A dead client has no padding to report; keep what was last known instead of guessing.
        if (is_stream_dead(status)) mark_stream_dead_locked(status, EN_FRAMES);
        return g_last_played;
    }
    const uint64_t queued = std::min<uint64_t>(padding, g_audio_submitted_frames);
    const uint64_t value = g_audio_played_offset + g_audio_submitted_frames - queued;
    g_last_played = value;
    g_played_cache = value;
    return value;
}

void remember_played_and_reset_locked() {
    g_audio_played_offset = played_frames_locked();
    g_audio_submitted_frames = 0;
    g_last_played = g_audio_played_offset;
    g_played_cache = g_audio_played_offset;
}

/**
 * Moves the stream to wherever the request resolves now, or rebuilds a dead one. What was queued
 * on the old endpoint is dropped — a fifth of a second at most — and only what was actually heard
 * is counted as played.
 *
 * Returns at once while the back-off from an earlier failure has not elapsed; the caller then
 * sees no client and lets time pass outside the lock. Never loops, never sleeps.
 */
void reroute_locked() {
    if (g_audio_channels == 0) {
        g_audio_reroute = false;
        g_audio_invalidated = false;
        return;
    }
    const bool forced = g_audio_invalidated.load();
    if ((forced || g_audio_client == nullptr) && GetTickCount64() < g_next_rebuild_ms) return;

    g_audio_reroute = false;
    trace_add(EV_REROUTE, forced ? 1 : 0, g_audio_client == nullptr ? 1 : 0);
    IMMDevice* device = resolve_device_locked(g_audio_wanted);
    if (device != nullptr && !forced && g_audio_client != nullptr &&
        endpoint_id(device) == g_audio_endpoint_id) {
        device->Release();  // already there, and the stream is healthy
        return;
    }
    const int32_t hash = device != nullptr ? endpoint_hash(endpoint_id(device)) : 0;
    trace_add(EV_REBUILD_BEGIN, hash, forced ? 1 : 0);
    remember_played_and_reset_locked();
    release_client_locked();
    g_audio_invalidated = false;
    if (device == nullptr) {
        set_error_text(L"Windows has no audio endpoint to play on");
        trace_error(EV_REBUILD_END, hash, static_cast<int32_t>(E_FAIL));
        schedule_retry_locked();
        return;
    }
    const HRESULT built = start_client_locked(device);
    device->Release();
    if (SUCCEEDED(built)) {
        // The failure counter is cleared by the first write the new stream accepts, not here:
        // a stream that dies again at once must keep backing off.
        trace_add(EV_REBUILD_END, hash, 0);
    } else {
        trace_error(EV_REBUILD_END, hash, static_cast<int32_t>(built));
        schedule_retry_locked();
    }
}

int open_audio(const std::wstring& wanted, int sample_rate, int channels, int bytes_per_sample, bool floating) {
    AudioLock guard(EN_OPEN);
    release_audio_locked();
    set_error_text(L"");
    g_error_state = false;

    ComScope com;
    if (!com.ready()) {
        set_audio_error(L"COM initialization", com.result);
        return 0;
    }

    IMMDevice* device = resolve_device_locked(wanted);
    if (device == nullptr) {
        if (copy_error_text().empty()) set_error_text(L"Windows has no audio endpoint to play on");
        return 0;
    }
    g_audio_wanted = wanted;
    g_audio_rate = sample_rate;
    g_audio_channels = static_cast<UINT32>(channels);
    g_audio_bytes_per_sample = static_cast<UINT32>(bytes_per_sample);
    g_audio_float = floating;
    g_audio_started = true;
    trace_add(EV_OPEN, endpoint_hash(endpoint_id(device)), sample_rate);
    const HRESULT built = start_client_locked(device);
    device->Release();
    if (FAILED(built)) {
        release_audio_locked();
        return 0;
    }
    g_audio_reroute = false;
    return static_cast<int>(g_audio_buffer_frames * g_audio_channels * g_audio_bytes_per_sample);
}

int write_audio(const float* samples, int sample_count, float gain) {
    if (samples == nullptr || sample_count <= 0) return 0;
    AudioLock guard(EN_WRITE);
    ComScope com;
    if (!com.ready() || g_audio_channels == 0) return 0;

    const UINT32 total_frames = static_cast<UINT32>(sample_count) / g_audio_channels;
    UINT32 frame_at = 0;
    bool failed = false;
    // A device that keeps its buffer full without ever draining it is dead too (no HRESULT says so).
    UINT32 last_padding = 0xFFFFFFFFu;
    ULONGLONG progress_at = GetTickCount64();
    while (frame_at < total_frames) {
        if (g_audio_invalidated || g_audio_reroute || g_audio_client == nullptr) reroute_locked();
        if (g_audio_client == nullptr || g_audio_invalidated) {
            // Nowhere to play (no endpoint, a rebuild failed, or the back-off has not elapsed). Let
            // the rest of the block pass in real time, counted as played, so the track neither
            // races ahead nor wedges this thread until a device appears. The wait is outside the
            // lock; the next write tries again once the back-off allows.
            const UINT32 skipped = total_frames - frame_at;
            g_audio_played_offset += skipped;
            g_last_played = g_audio_played_offset;
            g_played_cache = g_audio_played_offset;
            frame_at = total_frames;
            const int rate = g_audio_rate > 0 ? g_audio_rate : 48'000;
            const DWORD wait = static_cast<DWORD>(std::max<uint64_t>(50, uint64_t{skipped} * 1000 / rate));
            guard.unlock();
            Sleep(wait);
            return static_cast<int>(frame_at * g_audio_channels * g_audio_bytes_per_sample);
        }
        UINT32 padding = 0;
        HRESULT status = g_audio_client->GetCurrentPadding(&padding);
        if (FAILED(status)) {
            // Dead stream (unplugged, or Windows reconfigured it for an effect): rebuild, with
            // back-off. The loop cannot spin: the next pass either has a new client or sleeps.
            if (is_stream_dead(status)) {
                mark_stream_dead_locked(status, EN_WRITE);
                continue;
            }
            set_audio_error(L"Reading WASAPI buffer state", status);
            failed = true;
            break;
        }
        const UINT32 available = padding < g_audio_buffer_frames ? g_audio_buffer_frames - padding : 0;
        if (available == 0) {
            const ULONGLONG now = GetTickCount64();
            if (padding != last_padding) {
                last_padding = padding;
                progress_at = now;
            } else if (g_audio_started && now - progress_at > 1500) {
                trace_error(EV_STALL, static_cast<int32_t>(padding), static_cast<int32_t>(now - progress_at));
                mark_stream_dead_locked(kEDeviceInvalidated, EN_WRITE);
                last_padding = 0xFFFFFFFFu;
                progress_at = now;
                continue;
            }
            guard.unlock();
            Sleep(2);
            guard.lock();
            continue;
        }
        last_padding = 0xFFFFFFFFu;
        progress_at = GetTickCount64();
        const UINT32 frames = std::min(available, total_frames - frame_at);
        BYTE* target = nullptr;
        status = g_audio_render->GetBuffer(frames, &target);
        if (FAILED(status) || target == nullptr) {
            if (FAILED(status) && is_stream_dead(status)) {
                mark_stream_dead_locked(status, EN_WRITE);
                continue;
            }
            set_audio_error(L"Acquiring the WASAPI buffer", status);
            failed = true;
            break;
        }
        const size_t first = static_cast<size_t>(frame_at) * g_audio_channels;
        const size_t values = static_cast<size_t>(frames) * g_audio_channels;
        if (g_audio_float) {
            auto* output = reinterpret_cast<float*>(target);
            for (size_t index = 0; index < values; ++index) {
                output[index] = std::clamp(samples[first + index] * gain, -1.0f, 1.0f);
            }
        } else if (g_audio_bytes_per_sample == 2) {
            auto* output = reinterpret_cast<int16_t*>(target);
            for (size_t index = 0; index < values; ++index) {
                const float value = std::clamp(samples[first + index] * gain, -1.0f, 1.0f);
                output[index] = static_cast<int16_t>(value * 32767.0f);
            }
        } else {
            auto* output = reinterpret_cast<int32_t*>(target);
            for (size_t index = 0; index < values; ++index) {
                const double value = std::clamp(static_cast<double>(samples[first + index] * gain), -1.0, 1.0);
                output[index] = static_cast<int32_t>(value * 2147483647.0);
            }
        }
        status = g_audio_render->ReleaseBuffer(frames, 0);
        if (FAILED(status)) {
            if (is_stream_dead(status)) {
                mark_stream_dead_locked(status, EN_WRITE);
                continue;
            }
            set_audio_error(L"Submitting the WASAPI buffer", status);
            failed = true;
            break;
        }
        frame_at += frames;
        g_audio_submitted_frames += frames;
        if (g_rebuild_failures != 0) {
            // The new stream took audio: the incident is over.
            g_rebuild_failures = 0;
            g_next_rebuild_ms = 0;
            set_error_text(L"");
            g_error_state = false;
            trace_add(EV_RECOVERED, 0, 0);
            g_flush_requested = true;
        }
    }
    const int result = static_cast<int>(frame_at * g_audio_channels * g_audio_bytes_per_sample);
    if (failed) {
        // An error the engine may retry at once: give it a breather, outside the lock.
        guard.unlock();
        Sleep(10);
    }
    return result;
}

jobjectArray list_devices(JNIEnv* env) {
    std::vector<std::wstring> values;
    {
        // AUDIO FIX: no g_audio_mutex here. The enumerator is shared and thread-safe; only its
        // creation takes g_enum_mutex.
        ComScope com;
        IMMDeviceEnumerator* enumerator = nullptr;
        if (com.ready() && com.mta()) {
            enumerator = get_enumerator();
        } else if (com.ready()) {
            trace_error(EV_STA_REFUSED, 1, 0);
        }
        IMMDeviceCollection* devices = nullptr;
        if (enumerator != nullptr &&
            SUCCEEDED(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &devices)) &&
            devices != nullptr) {
            UINT count = 0;
            devices->GetCount(&count);
            for (UINT index = 0; index < count; ++index) {
                IMMDevice* device = nullptr;
                if (FAILED(devices->Item(index, &device)) || device == nullptr) continue;
                values.push_back(endpoint_id(device));
                values.push_back(property_string(device, PKEY_Device_FriendlyName));
                device->Release();
            }
            devices->Release();
        }
    }
    jclass string_type = env->FindClass("java/lang/String");
    if (string_type == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), string_type, nullptr);
    env->DeleteLocalRef(string_type);
    if (result == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    for (size_t index = 0; index < values.size(); ++index) {
        jstring value = env->NewString(
            reinterpret_cast<const jchar*>(values[index].c_str()), static_cast<jsize>(values[index].size()));
        if (value == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            continue;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
    }
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeInstall(
    JNIEnv* env, jclass, jstring title) {
    return install_frame(find_window(env, title)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeMinimize(JNIEnv*, jclass) {
    return send_system_command(SC_MINIMIZE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeSetBackdrop(JNIEnv*, jclass, jint kind) {
    return set_backdrop(g_window, static_cast<int>(kind)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeStartDrag(JNIEnv*, jclass) {
    HWND window = g_window;
    return window != nullptr && IsWindow(window) &&
        PostMessageW(window, WM_BITCHORD_DRAG, 0, 0) != 0
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeToggleMaximize(JNIEnv*, jclass) {
    HWND window = g_window;
    if (window == nullptr || !IsWindow(window)) return JNI_FALSE;
    return send_system_command(IsZoomed(window) ? SC_RESTORE : SC_MAXIMIZE)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeLastSize(JNIEnv* env, jclass) {
    jint values[6] = {
        g_size_counter.load(), g_size_type.load(), g_size_rect[0].load(),
        g_size_rect[1].load(), g_size_rect[2].load(), g_size_rect[3].load()};
    jintArray result = env->NewIntArray(6);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 6, values);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeDisplayGeneration(JNIEnv*, jclass) {
    return static_cast<jlong>(g_display_generation.load());
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativePostureGeneration(JNIEnv*, jclass) {
    return static_cast<jlong>(g_posture_generation.load());
}

/** Bit 0: tablet (slate) mode right now. Bit 1: the machine has touch/tablet hardware. Bit 2: docked. */
JNIEXPORT jint JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativePosture(JNIEnv*, jclass) {
    jint flags = 0;
    // SM_CONVERTIBLESLATEMODE is 0 in slate mode and 1 in laptop mode.
    if (GetSystemMetrics(SM_CONVERTIBLESLATEMODE) == 0) flags |= 1;
    // Touch hardware: a tablet PC, or an integrated/external touch digitizer.
    if (GetSystemMetrics(SM_TABLETPC) != 0 || (GetSystemMetrics(SM_DIGITIZER) & 0x3) != 0) flags |= 2;
    if (GetSystemMetrics(SM_SYSTEMDOCKED) != 0) flags |= 4;
    return flags;
}

/** {left, top, right, bottom, dpi} of the work area of the monitor the window is on, in pixels. */
JNIEXPORT jintArray JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeWorkArea(JNIEnv* env, jclass) {
    HWND window = g_window;
    RECT work{};
    if (window == nullptr || !IsWindow(window) || !work_area_of(window, &work, nullptr)) return nullptr;
    jint values[5] = {work.left, work.top, work.right, work.bottom, static_cast<jint>(GetDpiForWindow(window))};
    jintArray result = env->NewIntArray(5);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 5, values);
    return result;
}

/**
 * Asks DWM to redraw the frame and re-applies the frame margins. The same thing a maximize and
 * restore does to a window whose composed surface went stale, without moving anything.
 */
JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeRefreshFrame(JNIEnv*, jclass) {
    HWND window = g_window;
    if (window == nullptr || !IsWindow(window)) return JNI_FALSE;
    const int kind = g_backdrop_kind.load();
    const bool material = kind == 2 || kind == 3;
    MARGINS margins = material ? MARGINS{-1, -1, -1, -1} : MARGINS{1, 1, 1, 1};
    DwmExtendFrameIntoClientArea(window, &margins);
    SetWindowPos(
        window, nullptr, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    RedrawWindow(window, nullptr, nullptr, RDW_INVALIDATE | RDW_ALLCHILDREN | RDW_UPDATENOW | RDW_FRAME);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    // AUDIO FIX: remember the VM and look the callback up now, on a thread that can see the app's
    // classes, instead of FindClass on a COM thread for every callback.
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm != nullptr && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        cache_callback(env);
    }
    return JNI_VERSION_1_6;
}

/** AUDIO FIX: where audio-trace.log goes (the folder desktop.log lives in). Starts the watchdog. */
JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeSetTracePath(JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return;
    const jchar* chars = env->GetStringChars(path, nullptr);
    if (chars == nullptr) return;
    const jsize length = env->GetStringLength(path);
    std::wstring value(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(path, chars);
    trace_set_path(value);
}

/** AUDIO FIX: asks the watchdog to write the trace now (the Kotlin freeze watchdog calls it). */
JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeFlushTrace(JNIEnv*, jclass) {
    g_flush_requested = true;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeOpen(
    JNIEnv* env,
    jclass,
    jstring device,
    jint sample_rate,
    jint channels,
    jint bytes_per_sample,
    jboolean floating) {
    if (g_vm == nullptr) env->GetJavaVM(&g_vm);
    cache_callback(env);
    const jchar* chars = env->GetStringChars(device, nullptr);
    const jsize length = env->GetStringLength(device);
    std::wstring wanted(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(device, chars);
    return static_cast<jint>(open_audio(
        wanted,
        static_cast<int>(sample_rate),
        static_cast<int>(channels),
        static_cast<int>(bytes_per_sample),
        floating == JNI_TRUE));
}

JNIEXPORT jobjectArray JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeDevices(JNIEnv* env, jclass) {
    if (g_vm == nullptr) env->GetJavaVM(&g_vm);
    cache_callback(env);
    return list_devices(env);
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeWrite(
    JNIEnv* env, jclass, jfloatArray samples, jint count, jfloat gain) {
    if (samples == nullptr || count <= 0) return 0;
    const jsize available = env->GetArrayLength(samples);
    const jint safe_count = std::min(count, static_cast<jint>(available));
    jboolean copied = JNI_FALSE;
    auto* values = env->GetFloatArrayElements(samples, &copied);
    if (values == nullptr) return 0;
    const int written = write_audio(values, static_cast<int>(safe_count), static_cast<float>(gain));
    env->ReleaseFloatArrayElements(samples, values, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeFramesPlayed(JNIEnv*, jclass) {
    // AUDIO FIX: never queue behind a stream rebuild. If the stream is busy, answer with the
    // last count computed instead of waiting.
    std::unique_lock<std::mutex> guard(g_audio_mutex, std::try_to_lock);
    if (!guard.owns_lock()) return static_cast<jlong>(g_played_cache.load());
    ComScope com;
    return static_cast<jlong>(played_frames_locked());
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativePause(JNIEnv*, jclass) {
    AudioLock guard(EN_PAUSE);
    ComScope com;
    if (!g_audio_started) return;
    g_audio_started = false;
    if (g_audio_client != nullptr) {
        const HRESULT status = g_audio_client->Stop();
        if (FAILED(status) && is_stream_dead(status)) mark_stream_dead_locked(status, EN_PAUSE);
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeResume(JNIEnv*, jclass) {
    AudioLock guard(EN_RESUME);
    if (g_audio_started && !g_audio_reroute && !g_audio_invalidated) return;
    ComScope com;
    // A device change (or an invalidated stream) while paused is acted on here, before anything
    // is heard on the old one.
    if (g_audio_reroute || g_audio_invalidated) reroute_locked();
    if (!g_audio_started) {
        g_audio_started = true;
        if (g_audio_client != nullptr) {
            const HRESULT status = g_audio_client->Start();
            if (FAILED(status) && is_stream_dead(status)) mark_stream_dead_locked(status, EN_RESUME);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeFlush(JNIEnv*, jclass) {
    AudioLock guard(EN_FLUSH);
    ComScope com;
    if (g_audio_client == nullptr) {
        g_audio_submitted_frames = 0;  // a dead or missing stream holds nothing
        return;
    }
    g_audio_client->Stop();
    remember_played_and_reset_locked();
    HRESULT status = g_audio_client->Reset();
    if (FAILED(status) && is_stream_dead(status)) {
        mark_stream_dead_locked(status, EN_FLUSH);
        return;
    }
    if (g_audio_started) {
        status = g_audio_client->Start();
        if (FAILED(status) && is_stream_dead(status)) mark_stream_dead_locked(status, EN_FLUSH);
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeDrain(JNIEnv*, jclass) {
    // AUDIO FIX: bounded. Waits only while a running stream's padding keeps falling, for at most
    // 3 s in all and 1 s without progress (the buffer is 200 ms), and not at all when paused,
    // when nothing would ever drain.
    const ULONGLONG begin = GetTickCount64();
    ULONGLONG progress_at = begin;
    UINT32 last_padding = 0xFFFFFFFFu;
    for (;;) {
        {
            AudioLock guard(EN_DRAIN);
            ComScope com;
            if (g_audio_client == nullptr || !g_audio_started) return;
            UINT32 padding = 0;
            const HRESULT status = g_audio_client->GetCurrentPadding(&padding);
            if (FAILED(status)) {
                if (is_stream_dead(status)) mark_stream_dead_locked(status, EN_DRAIN);
                return;
            }
            if (padding == 0) return;
            const ULONGLONG now = GetTickCount64();
            if (padding != last_padding) {
                last_padding = padding;
                progress_at = now;
            }
            if (now - progress_at > 1000 || now - begin > 3000) {
                trace_error(EV_DRAIN_TIMEOUT, static_cast<int32_t>(padding), static_cast<int32_t>(now - begin));
                return;
            }
        }
        Sleep(2);
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeClose(JNIEnv*, jclass) {
    AudioLock guard(EN_CLOSE);
    ComScope com;
    release_audio_locked();
}

// AUDIO FIX: the two getters below take only the leaf g_info_mutex, never g_audio_mutex, so they
// answer at once even while a stream is being rebuilt (the pipeline panel reads the device name
// from the UI thread).
JNIEXPORT jstring JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeDeviceName(JNIEnv* env, jclass) {
    const std::wstring name = copy_device_name();
    return env->NewString(
        reinterpret_cast<const jchar*>(name.c_str()),
        static_cast<jsize>(name.size()));
}

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeLastError(JNIEnv* env, jclass) {
    const std::wstring text = copy_error_text();
    return env->NewString(
        reinterpret_cast<const jchar*>(text.c_str()),
        static_cast<jsize>(text.size()));
}

}  // extern "C"
