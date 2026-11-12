# Changelog

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
