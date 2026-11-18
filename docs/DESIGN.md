# Interface design

Plum and slate: primary `#654a85`, canvas `#f8f7fa`, foreground `#2b2634`. Material UI supplies themed action controls; the custom layout keeps this application's identity. Inter is bundled locally, with no external font request. Semantic success, warning, and error colors remain separate from brand colors.

Theme: `frontend/src/theme.ts`. Layout styles: `style.css`; shared accessibility and component refinements: `design.css`. Keyboard focus is visible, reduced motion is respected, and mobile breakpoints retain existing workflows.

## Verification

Production frontend build passed. Desktop and mobile layouts were checked against local APIs, including form and navigation behavior. Primary-button text contrast exceeds 4.5:1, and production frontend dependency audit reports zero vulnerabilities.

[Desktop preview](design-desktop.jpg) · [Mobile preview](design-mobile.jpg)
