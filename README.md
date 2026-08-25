# Weekly Budget

A voice-first budget tracker for Android. Tap **Speak** and say things like
*"40 dollars at Safeway"* or *"got paid 500 from work"* — the app parses the
amount, merchant, and type, auto-categorizes it, and files it into a weekly
income/expense view.

## Install

Download **WeeklyBudget.apk** from the
[latest release](https://github.com/Strongpaw/weekly-budget/releases/latest),
open it on your phone, and allow the install. The app checks for updates on
launch and offers new versions automatically.

## Features

- Voice entry with a confirmation sheet (plus an "Add more" button if you get cut off)
- Auto-categorization that **learns from your corrections** per merchant
- Week and Month views with income/expense totals and a per-week bar graph
- Weekly spending cap with warning colors (green → orange at 80% → red over)
- Home screen widget: this week's spend, cap status, and a one-tap mic
- Custom categories (add / rename / hide), plus search across all history
- Styled Excel (.xlsx) export, CSV backup, and bank statement CSV import with
  duplicate detection
- Everything stays on your phone — no accounts, no cloud, no permissions
  beyond internet for the update check

## Building

Requires JDK 17 and an Android SDK (API 35). Point `local.properties` at your
SDK, then:

```
gradle assembleRelease
```

`release.ps1` builds, tests, tags, and publishes a GitHub release with the
update manifest.
