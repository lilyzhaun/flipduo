# Changelog

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
