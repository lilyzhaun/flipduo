# Changelog

## 0.7.17

Changes since 0.7.14:

- Add an optional native long-press menu for notifications in the expanded, unlocked cover-screen notification shade. The switch is off by default under Settings → Cover screen behavior → System shortcuts.
- Rename the Fullscreen apps page to Cover app display (外屏应用显示). Each app keeps its saved display mode and content size, including custom sizes in Follow system mode.
- Open Settings directly from Home and load settings rows as they scroll into view. The cover-screen preview is now in Layout.

Reload System UI after updating to load the notification menu option. Its dispatch paths were checked against the MIX Flip HyperOS 3 and MIX Flip 2 HyperOS 3 and 4 ROMs; on-device menu behavior remains unverified.

## 0.7.14

Changes since the complete 0.7.9 release (`v0.7.9-full`):

- Add an optional fix for Control Center cards staying offset after rotating the cover screen back to portrait. It is off by default and works independently of landscape adaptation.
- With landscape adaptation enabled, the notification shade and Control Center stay open during layout updates. With the cameras at the top, the status icons stay above the brightness and volume sliders instead of jumping onto them. With the cameras at the bottom, the clear-notifications button sits beside them, aligned with their center and clear of the cards.
- The cover launcher Search button now provides tap feedback when haptics are enabled. Swipe left to leave search; vertical scrolling and text editing keep their normal behavior.
- The opt-in Rust Gallery regular-layout experiment no longer requires an exact device, firmware, Gallery version, or split-package configuration. The Java Gallery path is unchanged. Full access to the native Gallery home screen still needs device confirmation.

The landscape status-icon and clear-button changes were confirmed on MIX Flip 2 / HyperOS 4. Layout and rotation checks also used the separate MIX Flip and MIX Flip 2 HyperOS 3 ROMs; the new behavior has not been verified on either HyperOS 3 device.

## 0.7.9

Changes since 0.7.4:

- Choose the regular layout on the cover screen for supported Xiaomi apps: Calendar, Contacts and Dialer, Clock, Messaging, Recorder, Calculator, and compatible Gallery versions. This is off by default and can be enabled per app.
- A separate experimental Gallery switch targets MIX Flip 2 on `OS4.0.4.0.XOHCNXM` with Rust Gallery `5.4.3.12-1116-cn`. It preserves the actual window size and scaling; access to the full Gallery home screen still needs device confirmation.
- Set cover-screen content size per app from 70% to 110% in Follow system mode. Restore 100% for the system default without changing the app's fullscreen scale.
- The clock preview follows seconds and time-zone changes, and long clock formats stay within their lines. App search now shows the right icons when rows are reused.
- Control Center detail panels keep a stable top margin when the cover status bar is hidden. If a host component cannot start, other status and event controls can still load.
- On the first-generation MIX Flip running HyperOS 3, closing the notification list opened from the cover-screen bell now restores access to the side controls. There is no need to open a notification first.

## 0.7.4

Changes since 0.6.150:

- Choose three or four columns in FlipHome's native cover app list. Scrolling, editing and drag-to-reorder remain available.
- Search installed, launchable apps by name or package and open a result on the cover screen. The optional Search button matches the stock Edit button's size and rounded ends. Searches stay on the device and have no history.
- Optionally double-tap a blank part of the cover app list to lock the screen (off by default).
- Choose separate tap actions for the status indicator and the clock under Control Area Actions. They open Wi-Fi settings and Clock by default; Calendar is also available. Existing navigation long-press settings are retained.
- Customize the clock's two lines independently, with a format preview and optional seconds.

## 0.6.150

Cumulative changes since 0.6.147:

- Restore native long-press actions for cover-screen control-center buttons.
- Make native detail panels more compact and keep their content outside the camera cutout, while preserving scrolling and usable touch targets.
- Restore the control center's normal opacity and scale after returning from a long-press detail panel.
- Prevent stale app-interception decisions from overriding the cover-app eligibility switch, including previously blocked system apps. Temporary allow decisions no longer persist in this cache after disabling the option.

The control-center changes are user-accepted on MIX Flip 2 / HyperOS 4. The eligibility-cache correction has ROM and logic verification; affected apps on other target firmware still require device verification. Reload System UI for the control-center changes. The eligibility correction also requires the updated system-framework hooks, which load after a manual device restart.

## 0.6.147

Cumulative fixes since 0.6.144:

- Fix app-edge blur overlap during page transitions and recent-task gestures.
- Match corner fill colors to the side backdrop while preserving the existing corner radii.
- Fix the transient app-edge white strip when returning from the notification shade.
- Restore framed PNG screenshots with system Screenshot 1.6.3.47, retaining the verified 1.6.3.44 and older capture paths.

These fixes are user-accepted on MIX Flip 2 / HyperOS 4. Other target environments retain the verification limits described in the README. Reload FlipHome and System UI after updating so both hosts use the new module code.

## 0.6.144

- Fix the reported white-strip flash at the app edge when pulling down the notification shade or switching pages with live blur fill enabled.
- Preserve asymmetric corners with continuous app-edge restoration on firmware supporting per-corner transactions, while retaining the existing compatibility path elsewhere.

## 0.6.143

- Add the FlipDuo GitHub repository link to the About page.
- Let users choose Lanzou Cloud or the latest GitHub Release when getting updates. The Lanzou password is shown in the channel picker; links open only after selecting a channel.

## 0.6.142

Cumulative changes since 0.6.138:

- Add a home-screen quick-restart dialog for Screenshot, Sogou Input Method for Xiaomi, FlipHome, and System UI. Select individual scopes or all four and confirm to send restart requests for the current Android user. Root access is required; per-scope results distinguish sent requests, inactive processes, and failures.
- Use native miuix dialog buttons and a lightweight multi-select list, with select-all above the list and fixed bottom actions.
- Consolidate MixFlipMod attribution under Open-source licenses and include HyperCeiler attribution for the quick-restart flow.
- Restore framed cover-screen screenshots with system Screenshot 1.6.3.44, including the native hardware-to-software bitmap conversion. Retain the older modern and legacy screenshot paths, the original preview/editing images, and PNG transparency for framed exports.

The screenshot correction is user-accepted on MIX Flip 2 / HyperOS 4. Other target environments retain the verification limits described in the README.

## 0.6.138

Cumulative changes since the previous public source version, 0.6.118:

- Add names and renaming for the three local appearance presets, with clearer primary and secondary actions throughout settings.
- Add per-user cover-screen keyboard selection from enabled input methods, preserving the system's fold/unfold behavior and other modules' policy when no keyboard is selected. WeType is excluded from the picker.
- Share the live blurred backdrop with cover controls and retain readable control backgrounds while the source is unavailable. Pause live fill when system blur is unavailable, and improve transition and surface-release coordination.
- Extend the bottom-overlay option to application bottom compatibility insets on firmware providing the native exemption API. Restart affected apps after changing this option; first-generation HyperOS 3 retains overlay-only behavior.
- Correct recent-task long-press menu initialization and improve framed screenshot integration with the supported legacy capture pipeline.

Live blur remains firmware- and content-dependent. Compatibility targets are not universal device verification; see the README.

## 0.6.118

- Correct inverted application-side corners on the first-generation MIX Flip by applying its panel installation orientation to its own corner radii. The shared correction also covers recent-task transitions, previews, and framed screenshots.

## 0.6.117

- Publish the initial production-source distribution of FlipDuo.
- Correct full-width cover notifications when the firmware reconstructs the physical camera cutout.

See the matching version tag for the source corresponding to this release.
