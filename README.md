# overcontrol (大麦倒计时) — floating on-sale countdown

A small, movable overlay window that sits on top of Damai and shows **only** the
remaining time until the nearest upcoming on-sale (开抢/开票). No root, no Frida, no
network calls of its own — it reads the on-sale time straight off Damai's own
on-screen text via a scoped Android **AccessibilityService**, the same way a screen
reader would, and reformats it into a countdown.

## How it works

Damai renders its on-sale times as plain text — see
`reference/decompiled/sources/com/alibaba/pictures/bricks/util/DateUtil.java` and
`.../component/reservation/ReservationBean.java`:

```
8月20日10:00开抢     今天 10:00 开抢     明天 10:00 开抢
后天 10:00 开抢      08-20 10:00 开抢
```

`CountdownAccessibilityService` walks the visible node tree on every window/content
change in `cn.damai` looking for **`已预约`** — Damai's label for a concert you have
already reserved. That badge is the user stating which concert matters, so it, and only
it, is tracked; every other on-sale time on screen is ignored.

> An earlier version tracked whichever on-sale time was soonest across everything it had
> ever seen. That guessed at intent, and the soonest show on screen is rarely the one you
> care about.

When a page carries the marker, the same pass reads its on-sale time (`CountdownParser`)
plus the title, date, venue and price. Details are matched by *shape* rather than view id
— ids differ between Damai's list rows and its detail page, but a date, a venue and a `¥`
price look the same wherever they appear. `CountdownState` holds exactly one such show,
merging new sightings so a page that omits the venue doesn't blank it out.

Unlike the old behaviour, leaving Damai no longer discards it: the countdown has to keep
running while the user is in another app, since the entire point is to be back on the page
at T-0. A stale reservation ages out after 24h instead.

`OverlayService` is a small foreground service that draws a draggable pill
(`TYPE_APPLICATION_OVERLAY`) and ticks it once a second from that shared state. It
keeps running independent of Damai's lifecycle, surviving it being backgrounded or
killed.

The pill rests at the bottom-left, `BOTTOM_MARGIN_DP` above the bottom edge so it clears
Damai's own action bar. Its window uses `TOP|START` gravity regardless — that keeps the
drag maths aligned with `getRawY()` — with the resting position computed from the measured
height once the first layout pass has run. Drags are clamped to the screen (recomputed
from the touch anchor each move, so it un-sticks from an edge properly), and the pill
re-clamps whenever expanding makes it taller near the bottom.

Because it is an overlay it sits **above** Damai, so an injected tap landing inside the
pill would press the pill instead of the button underneath. Every click therefore runs
through `clickWithPillHidden`, which hides the pill, waits `TAP_HIDE_LEAD_MS` for the
window to drop out of hit-testing, dispatches, and restores it. That is what makes the
click correct no matter where the pill has been dragged.

`MainActivity` is the setup and control surface: permission rows with live ON/OFF chips,
what the gestures do, a show/hide toggle for the pill, and Exit. **Exit means exit** — it
stops the overlay *and* calls `disableSelf()` on the accessibility service, so nothing
keeps reading the screen after the user has closed the app. A quieter "hide pill" option
is offered alongside for when the permission should stay granted, since re-granting it
means a trip to system settings.

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

### Node recycling differs by API level — test on an old device

`AccessibilityNodeInfo.recycle()` returns the node to a real pool on API 30 and below:
touching a recycled node, or recycling one twice, throws `IllegalStateException`. On
API 31+ pooling was removed and `recycle()` is a no-op, so **both mistakes are completely
silent on a modern phone and fatal on an old one**. Three such bugs lived here and only
ever showed up on an API 28 Galaxy Note 8, as a repeating "Overcontrol keeps stopping":

- `extractLabel` recycled `cursor` *before* checking whether `getParent()` returned null,
  then read and re-recycled it when the climb hit the window root early. This ran on every
  window content change, so on any page where the on-sale text sits within four levels of
  the root it crashed continuously.
- `clickByText` / `clickByViewId` recycled the matched node and then recycled `root` in a
  `finally` — a double recycle whenever the match *was* the root.

The rule these now follow: never release a node until a valid replacement is in hand, and
never recycle a node an enclosing scope already owns. `onAccessibilityEvent` also wraps the
whole scan in a catch-all, because the tree belongs to another app and can be recycled out
from under a walk; an exception escaping that callback kills the process and takes the
overlay with it. `MainActivity` shows the last crash (recorded by `CrashLog`) so a trace
can be read off the phone when adb isn't attached.

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

### The reserved marker may never reach us — hence `ScreenLog`

The bottom action button contributes **no node at all**, whether it reads 预约抢票 or
已预约: a dump of the detail page shows only `帮助` and `想看` down there. So the reserved
state cannot be read from the button, and `RESERVED_MARKERS` lists several spellings
(`已预约`, `预约成功`, `取消预约`) in the hope that one of them is drawn somewhere that *is*
exposed. Each is unambiguous alone — an unreserved page never says 取消预约 — and the bare
`预约` badge on a tour-city tab deliberately matches none of them, which is what keeps an
unreserved page from turning the pill green.

When no marker is found, the scan records what the page *did* expose to `ScreenLog`, and
`MainActivity` shows it under **诊断 · 最近未识别的页面**. That exists because the phone
holding the reservation is rarely the one with adb attached: if the pill stays on
"waiting" over a reserved show, that card is the evidence needed to pick the right marker.

### Match button labels, not substrings

`clickByText` requires the node's text to *be* the label — equal to it, or containing it
while no more than `LABEL_SLACK` characters longer. A plain `contains` is not safe: the
detail page carries the sentence 实名制购票和入场 in its terms row, which contains 购票, and
a contains-match pressed that row and opened the 服务说明 sheet instead of the buy button.
Observed on device; the generic `购票` entry was dropped from `CLICK_TARGETS` as well, since
a two-character label is nearly all false positives.

### Details are matched by shape, and the price arrives in pieces

`pickDetails` takes the show's own date, its venue and its price. Two traps, both found on
a real page:

- `08月31日 11:50开抢` also looks like a date. It is the on-sale line — already the
  countdown — so any text containing 开抢/开票/开售 is excluded, and a full `2026.10.10-10.11`
  is preferred over a bare `月/日`.
- The price reaches accessibility as **two** nodes: `¥` and `380－980`. Matching `¥` alone
  yielded a detail line reading just "¥", so `priceAt` rejoins a lone currency symbol with
  the following text when that starts with a digit.

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

- **Auto: at T-0.** When the tracked concert's countdown reaches zero, Damai relabels its
  button from `已预约` to `立即预订` and `OverlayService.maybeBookNow` presses it. There is
  no dwell — T-0 *is* the moment, and a second late costs a ticket — so it fires on the
  first tick at or after the target, then retries every `BOOK_RETRY_MS` (1.5s) for
  `BOOK_WINDOW_MS` (2 min), because Damai doesn't always flip the button the instant the
  clock runs out. One success per concert, keyed by on-sale time so a different
  reservation starts over. It is gated on Damai being foreground: at T-0 the `已预约`
  marker is gone, so that is what keeps the tap from landing in another app. A miss dumps
  the tree to logcat (`DUMP_ON_MISS`) so a Damai redesign leaves evidence behind.
- **Manual: long-press the pill.** Presses the first match in
  `OverlayService.CLICK_TARGETS` immediately, no dwell — that array is the thing to edit
  when building a click feature for a different page.

Both report via toast whether anything matched. A successful booking press also plays
`ClickEffectView` around the pill — three staggered rings expanding through a soft glow
with a check badge popping in the centre. It gets **its own** overlay window rather than
living inside the pill's: the burst is far wider than the pill, and growing the pill's
window to fit would leave a rectangle of invisible padding swallowing taps meant for Damai
underneath. That window is `FLAG_NOT_TOUCHABLE` and removes itself when the animation
ends.

The pill shows `⚪` while waiting for a reserved show and `🟢` once it is tracking one,
then `🎯 抢票中…` → `✅ 已抢` through the booking. Expanded, it lists the concert's title,
date, venue and price.

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
