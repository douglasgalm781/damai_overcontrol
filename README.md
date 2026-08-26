# overcontrol (大麦倒计时) — floating on-sale countdown

A small, movable overlay window that sits on top of Damai and shows **only** the
remaining time until the nearest upcoming on-sale (开抢/开票). No root, no Frida, no
network calls of its own — it reads the on-sale time straight off Damai's own
on-screen text via a scoped Android **AccessibilityService**, the same way a screen
reader would, and reformats it into a countdown.

## How it works

Damai renders its on-sale times as plain text in a handful of fixed formats — see
`reference/decompiled/sources/com/alibaba/pictures/bricks/util/DateUtil.java` and
`.../component/reservation/ReservationBean.java`:

```
8月20日10:00开抢     今天 10:00 开抢     明天 10:00 开抢
后天 10:00 开抢      08-20 10:00 开抢
```

`CountdownAccessibilityService` is scoped (via `accessibility_service_config.xml`) to
only receive events from `cn.damai`. On every window/content change there it walks the
currently visible node tree, checks each `TextView`'s rendered text against those
formats (`CountdownParser`), and records every future on-sale time it finds into
`CountdownState` — not just the soonest one on the current screen. The scan itself never
taps, focuses, or types into anything — read-only `getText()`/`getChild()`.

`CountdownState` remembers every show seen this way, keyed by its on-sale time, and
always reports the soonest one that (a) hasn't passed yet and (b) was re-confirmed on
screen within the last 24h (a stale/rescheduled show falls out on its own, without the
overlay ever showing a wrong frozen time). This is what makes the overlay move on to
the next-nearest show automatically once the current one's sale time passes — even if
you've since navigated away from the screen that showed it.

`OverlayService` is a small foreground service that draws a draggable pill
(`TYPE_APPLICATION_OVERLAY`) and ticks it once a second from that shared state. It
keeps running independent of Damai's lifecycle, surviving it being backgrounded or
killed.

## Clicking (`NodeActions`)

Pressing things is a separate, explicitly-triggered concern from the countdown scan.
`NodeActions` holds the primitives; `CountdownAccessibilityService.peek()` hands out the
connected service to run them through:

```java
NodeActions.clickByText(svc, "立即购票", "预约抢票");  // first match wins
NodeActions.clickByViewId(svc, "cn.damai:id/btn_buy");
NodeActions.tapAt(svc, x, y);
NodeActions.dumpScreen(svc);   // every node + id + bounds -> adb logcat -s Overcontrol
```

`clickByText`/`clickByViewId` match against a *fresh* `getRootInActiveWindow()` (never a
node cached from an earlier scan — those are recycled and their bounds go stale), then
`clickNode` tries two things in order:

1. `ACTION_CLICK` on the matched node or the nearest clickable ancestor (up to 6 hops).
2. Failing that, a synthesized tap at the node's on-screen centre via `dispatchGesture` —
   this is what `android:canPerformGestures="true"` in the service config is for.

### Gesture taps and old Android

`tapAt` drags one pixel instead of emitting a move-only path. Android 9 (API 28) and
older decide whether a stroke path is empty from its **bounds**, and a lone `moveTo` has
bounds `(x,y,x,y)` — zero width and height, so `RectF.isEmpty()` is true and
`StrokeDescription` throws `IllegalArgumentException("Path is empty")`. Newer releases
test `Path.isEmpty()`, where the `moveTo` counts as a verb and the identical path is
accepted. A move-only tap therefore works on API 33 and crashes on API 28.

`tapAt` also refuses negative/NaN coordinates and checks
`CAPABILITY_CAN_PERFORM_GESTURES` before dispatching. That capability is bound when the
user *enables* the service, so after an in-place update of a service that was enabled
under a config without `canPerformGestures`, taps are silently dropped by the system
until accessibility access is toggled off and back on — the check turns that into a clear
message instead of a mystery no-op.

### The 预约抢票 button has no node at all

Measured on `ProjectDetailActivity` (Android 13, 1220×2712), by dumping the tree with
`NodeActions.dumpScreen` — `uiautomator dump` is useless here, it never reaches idle
because Damai's own on-page countdown ticks every second:

```
[click] [54,2523][261,2712]  ... id=cn.damai:id/project_item_bottom_customer_service_lv , _want_to_see_fl
[     ] [0,0][1220,2712]     cls=android.widget.FrameLayout  <- the ONLY node over the button
```

The orange 预约抢票 button contributes **zero** nodes: no text, no content-description,
no id, not even an anonymous clickable node. Nothing to match on, so `clickByText` can
never reach it — its neighbours 帮助 and 想看 are exposed, the button itself is a hole in
the tree.

`NodeActions.tapBesideAnchors` targets it without hardcoding pixels: union the bounds of
every node whose id contains `cn.damai:id/project_item_bottom_`, then tap between that
union's right edge and the right edge of the screen, at the union's vertical centre. On
the device above that resolves to anchors `[54,2523][261,2712]`, screen `[0,0][1220,2712]`
→ tap at `(740.5, 2617.5)` — dead centre of the button. A `minGapFraction` sanity check
makes an unexpected layout fail instead of tapping something arbitrary.

Two triggers are wired up:

- **Auto: 5s of 🟢.** The pill's 🟢 means the page on screen is the tracked show's own
  page (`CountdownState.isActive()`). Once that has held for `ACTIVE_DWELL_MS` = 5s
  without interruption, `OverlayService.maybeAutoClick` presses `AUTO_CLICK_TARGET`
  (`预约抢票`) on that page. The dwell is what keeps it off pages merely scrolled past —
  a single active scan isn't enough, the page has to still be there 5s later. The pill
  shows the countdown to it (`🟢 3s` → `🟢 ▶` → `🟢 ✔`), and a toast confirms the press.
  At most one successful press per 🟢 streak; it rearms when the status falls back to ⚪.
  A miss (target not on screen yet) retries every 2s while the streak lasts, and the first
  miss of a streak dumps the tree to logcat (`DUMP_ON_MISS`) so a Damai redesign that
  breaks the click leaves evidence behind.
- **Manual: long-press the pill.** Presses the first match in
  `OverlayService.CLICK_TARGETS` immediately, no dwell — that array is the thing to edit
  when building a click feature for a different page.

Both report via toast whether anything matched.

## Requires

Two ordinary, user-grantable Android permissions — **no root**:

1. **Accessibility access** for this app (Settings → Accessibility). Content is only
   ever read from `cn.damai`; see the in-app description of exactly what it reads and
   when it taps.
2. **Display over other apps** (the overlay permission).

`MainActivity` walks you through granting both and shows live status.

## Build

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. Install the APK, open **大麦倒计时 Overcontrol**, grant the two permissions.
2. Open Damai and browse to any page showing an on-sale time (search results, home
   feed, artist page, project detail).
3. The pill appears and starts counting down. Drag it anywhere on screen, tap it to
   show the show's title, long-press it to press that page's buy button.

## Scope

Same as the repo [README](../README.md): on a device/account you own. The countdown is
passive and display-only. Clicking presses a button you could have pressed yourself, on
the page already in front of you — either on a long-press, or automatically after 5s on
the tracked show's own page. It does not talk to Damai's servers.
