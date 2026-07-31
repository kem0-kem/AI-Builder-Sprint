# Design QA

## Evidence

- Source visual truth: `C:\Users\kjhlt\AppData\Local\Temp\codex-clipboard-81b977f9-939f-4d89-a7b2-33b063514b08.png`
- Implementation screenshot: `design-evidence/profile_overview_final.png`
- Profile editor screenshot: `design-evidence/profile_edit_from_pencil_final.png`
- Feed ratio screenshot: `design-evidence/feed_ratio_final.png`
- Full-view comparison: `design-evidence/profile_overview_comparison_final.png`
- Source pixels: 640 × 1211
- Implementation pixels: 1080 × 2424 on a Pixel 9 emulator
- Android viewport: 1080 × 2424 pixels, 420 dpi (approximately 411 × 923 dp)
- Density normalization: the source was resized to 540 × 1022. The implementation content region was cropped from the 1080 × 2424 device capture to exclude system UI/safe-area space, then resized to 540 × 1022. Both were placed in one 1080 × 1022 comparison image.
- State: profile overview, light theme, default profile data

## Full-view comparison evidence

The final implementation matches the supplied profile composition: notebook-paper background, top title and purple pencil affordance, centered illustrated avatar, name and two-line introduction, location pill, three-column statistics card, and the interests section. The same horizontal proportions and near-identical card heights are visible in `profile_overview_comparison_final.png`.

## Focused region comparison evidence

A separate crop was not needed because the normalized 1080 × 1022 comparison keeps the typography, avatar edge, icons, dividers, statistics, and interest copy readable at 1:1 inspection. The editor destination was inspected separately in `profile_edit_from_pencil_final.png`.

## Required fidelity surfaces

- Fonts and typography: Korean hierarchy, weights, line wrapping, and labels match the reference. Header, name, and feed-card type were reduced from the first pass to avoid oversized text.
- Spacing and layout rhythm: avatar, location, statistics, and interests now follow the reference’s compact vertical rhythm. Profile content is scrollable on shorter devices.
- Colors and visual tokens: warm paper background, subtle ruled lines, black ink, gray supporting copy, white cards, and purple accents remain consistent.
- Image quality and asset fidelity: a dedicated raster portrait is used with a circular crop and lavender background. It is sharp at the rendered 128 dp slot and follows the supplied illustration direction.
- Copy and content: name, introduction, location, statistics, and interests match the supplied image.

## Findings

- No actionable P0, P1, or P2 visual differences remain.
- [P3] Material outline icons differ slightly from the exact hand-drawn icon shapes in the reference.
  - Location: profile statistics and interests card.
  - Impact: minor stylistic variation only; meaning, hierarchy, color, and alignment remain intact.
  - Follow-up: replace them only if exact exported Figma icon assets become available.

## Comparison history

1. First pass: header/name typography and vertical gaps were too large, pushing the lower content down; feed cards also felt slightly oversized.
2. Fixes: reduced profile header/name/body type, tightened major vertical gaps and card padding, reduced feed title/body sizing and card padding, and added profile scrolling for shorter screens.
3. Post-fix evidence: `profile_overview_comparison_final.png` confirms the corrected proportions; `feed_ratio_final.png` confirms the denser feed layout.

## Interaction and runtime checks

- Feed top-right profile button opens the profile overview, not the editor.
- Profile pencil button opens the profile editor.
- Android system back returns editor → profile → feed.
- Selected feed bottom action shows `쓰기` and opens the feed composer.
- Gradle `assembleDebug` passed from `C:\Users\kjhlt\Desktop\APPTIVE_APP`.
- APK install and launch passed on the Pixel 9 emulator.
- Android runtime crash log check: no `AndroidRuntime` errors.

## Implementation checklist

- [x] Separate profile overview and edit routes.
- [x] Connect the feed profile button to the overview.
- [x] Connect only the profile pencil button to editing.
- [x] Match the corrected profile reference.
- [x] Refine profile and feed proportions.
- [x] Verify navigation, build, install, and runtime.

## Bottom navigation alignment update

- Source visual truth: `C:\Users\kjhlt\AppData\Local\Temp\codex-clipboard-9c9a6513-48d3-4523-9c36-1f66088b83d0.png`
- Source pixels: 528 × 150
- Implementation screenshot: `design-evidence/bottom_nav_centered_final.png`
- Implementation pixels: 1080 × 2424 on a Pixel 9 emulator at 420 dpi
- Focused comparison: `design-evidence/bottom_nav_comparison.png`
- State: feed selected, `쓰기` center action, light theme
- Normalization: the implementation bottom region was cropped and resized to 528 × 150, then placed beside the 528 × 150 source crop.
- Earlier P2 finding: Material `NavigationBar` reserved the gesture-navigation bottom inset inside the white surface, making the icon-and-label groups visibly top-heavy.
- Fix: the nested navigation bar now uses zero internal system insets, while the enclosing white surface and app-level safe area remain unchanged.
- Post-fix evidence: in the right side of `bottom_nav_comparison.png`, the icon-and-label groups are vertically centered inside the white rounded block with balanced space above and below.
- Typography: labels retain the same size, weight, and wrapping.
- Spacing: only the vertical alignment changed; horizontal thirds, touch targets, bar height, corner radius, and outer padding are unchanged.
- Colors and tokens: selected purple treatment, black inactive items, white surface, and shadow remain unchanged.
- Image quality: no raster assets are involved in this component; existing Material icons remain sharp.
- Copy: `내 대화`, `쓰기`, and `편지쓰기` are unchanged.
- Runtime: Gradle `assembleDebug`, APK installation, launch, and `AndroidRuntime` log checks passed.

## System bar safe-area update

- Implementation screenshot: `design-evidence/system_bars_safe_area_final.png`
- Viewport: Pixel 9 emulator, 1080 × 2424 pixels at 420 dpi
- State: feed selected, light theme, gesture navigation enabled
- Fix: a shared `safeDrawingPadding()` boundary now wraps every app screen.
- Top result: headers and interactive content begin below the OS status bar.
- Bottom result: the rounded app navigation surface ends above the OS gesture-navigation region; its items remain centered inside the app-owned white surface.
- Scope: the fix applies to feed, conversations, letters, details, composers, profile overview, and profile editing without per-screen duplication.
- Runtime: final build, APK installation, launch, and Android runtime error check passed.

## Interest settings flow

- Source visual truth: `C:\Users\kjhlt\AppData\Local\Temp\codex-clipboard-fb24770f-c394-4527-829d-873f7f0e87d8.png`
- Source pixels: 1034 × 1549
- Implementation screenshot: `design-evidence/interests_mobile_balanced.png`
- Selected-state screenshot: `design-evidence/interests_mobile_selected.png`
- Completion screenshot: `design-evidence/interests_mobile_complete.png`
- Full comparison: `design-evidence/interests_comparison_final.png`
- Viewport: Pixel 9 emulator, 1080 × 2424 pixels at 420 dpi (approximately 411 × 923 dp)
- State: profile → interest settings, no selection, light theme
- Normalization: system-owned status and gesture regions were excluded from the implementation crop; source and implementation were compared at the same 517-pixel width. The source frame is shorter than the Pixel 9 viewport, so vertical whitespace below the list is treated as a device-aspect difference rather than app layout drift.
- Fonts and typography: title, guidance copy, selection counter, section headings, chips, and completion label preserve the reference hierarchy while remaining readable on a physical phone.
- Spacing and layout rhythm: the reference’s five-column structure is retained. Chip heights were set to 40 dp for recommended items and 36 dp for the denser secondary list to balance touch comfort and full-list visibility.
- Colors and tokens: paper background, ruled lines, purple outlines, selected fill, white chips, and purple completion button match the existing app system and supplied reference.
- Image quality and icons: the screen uses Material outline icons matching each interest; no placeholder images, emoji, or handcrafted graphics are used.
- Copy and content: all recommended and full-interest labels, guidance text, counter, search placeholder, and completion copy match the supplied screen.

### Comparison history

1. Initial P2: controls were too large and pushed the full list below the useful viewport.
2. First fix: controls were reduced to match the dense reference, but this made tap targets too small for a real phone and the compact Material text field clipped its placeholder.
3. Final fix: replaced the search field with a properly centered compact input and rebalanced chip heights, icon sizes, typography, and spacing around real phone ergonomics. `interests_mobile_balanced.png` is the post-fix evidence.

### Interaction checks

- Tapping the profile interest card opens interest settings.
- Search input filters recommended and full-interest data.
- Interests toggle selected/unselected state.
- Selection is capped at three; a fourth tap does not increase the `3 / 3` count.
- Completion returns to the profile screen only when three interests are selected.
- Back arrow, profile icon, and Android system back return to the profile.
- Gradle build, APK install, launch, and Android runtime error checks passed.

final result: passed

## Letter home flow

- Source visual truth: `C:\Users\kjhlt\AppData\Local\Temp\codex-clipboard-5474b73e-aca3-4432-9ef4-f35447b39faa.png`
- Source pixels: 1204 × 2211
- Implementation screenshot: `design-evidence/letter_home_final.png`
- Implementation pixels: 1080 × 2424 on a Pixel 9 emulator at 420 dpi (approximately 411 × 923 dp)
- Full-view comparison: `design-evidence/letter_home_comparison.png`
- Focused header/card comparison: `design-evidence/letter_home_focus_comparison.png`
- State: bottom navigation `편지쓰기` tapped, letter home selected, light theme
- Density normalization: the reference app region was cropped to 1070 × 2131 pixels and the implementation app region to 1080 × 2160 pixels, excluding device-owned framing/status/gesture areas. Each was resized to the same 540-pixel comparison width.

### Required fidelity surfaces

- Fonts and typography: the Korean title hierarchy, two-line heading, card label, supporting copy, CTA, and report labels reproduce the reference wrapping and visual emphasis. The main heading was reduced to 24 sp after the first device capture to avoid oversized display text.
- Spacing and layout rhythm: 24 dp page margins, a 300 dp feature card, compact report card, rounded 26 dp card corners, and the existing safe-area-aware bottom bar preserve the reference’s relaxed vertical rhythm without overcrowding a physical phone viewport.
- Colors and visual tokens: warm ruled paper, pale lavender feature surface, purple CTA/selected state, peach report accent, black ink, and muted gray copy map to the existing app tokens and the reference palette.
- Image quality and asset fidelity: the envelope, cream letter, peach pencil, botanical sprig, and heart use a dedicated 1456 × 1090 raster illustration generated for the card. The final full-card crop keeps the entire envelope and pencil visible with no stretching, halos, or placeholder graphics.
- Copy and content: `자연스러운 연결`, the main question, `오늘의 편지`, letter prompt, `편지 쓰기`, `오늘의 회고 리포트`, and its supporting sentence match the supplied reference.

### Findings

- No actionable P0, P1, or P2 differences remain.
- [P3] The implementation CTA uses the app’s solid purple token while the reference appears as a softer lavender gradient.
  - Location: `TodayLetterCard`.
  - Impact: minor stylistic drift only; contrast and action hierarchy are stronger and remain consistent with the existing app.
  - Follow-up: introduce a branded gradient only if an exact Figma color specification or exported asset becomes available.
- [P3] Material outline icons differ slightly from the source’s hand-drawn icon treatment.
  - Location: profile, report, and bottom navigation icons.
  - Impact: semantic meaning, tap affordance, alignment, and sizing remain intact.

### Comparison history

1. First pass P2: the 29 sp heading and 344 dp feature card were visibly larger than the supplied mobile composition and increased above-the-fold density.
2. First fix: reduced the heading to 24 sp, feature card to 300 dp, illustration region, body typography, and report-card padding. Post-fix evidence showed the intended phone-scale hierarchy.
3. Second pass P2: the illustration’s lower crop hid part of the envelope and pencil, unlike the source.
4. Final fix: placed the generated 4:3 artwork across the full feature-card bounds so the full subject remains visible. `letter_home_comparison.png` and `letter_home_focus_comparison.png` are the post-fix evidence.

### Interaction and runtime checks

- Tapping the feed screen’s bottom `편지쓰기` item opens the new letter home.
- Tapping the feature card or `편지 쓰기` CTA opens the existing letter composer.
- Android system back from the composer returns to the letter home.
- Tapping `오늘의 회고 리포트` opens the previous-letter list.
- The selected third bottom item becomes `이전편지` and also opens the previous-letter list.
- The top profile button remains connected to the profile overview.
- Gradle `assembleDebug`, APK installation, launch, and Android runtime crash-log checks passed.

final result: passed
