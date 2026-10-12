# ChapterPlayer — Getting Started

This guide will get you from zero to running ChapterPlayer on your phone or emulator.

---

## Step 1 — Install Android Studio

1. Go to **https://developer.android.com/studio** and download Android Studio.
2. Run the installer. Accept defaults — the installer will guide you through.
3. On the **SDK Components** screen, make sure these are checked:
   - Android SDK
   - Android SDK Platform (API 34 — Android 14)
   - Android Emulator
   - Android SDK Platform-Tools
4. Complete the wizard. This will take a few minutes as it downloads the SDK.

---

## Step 2 — Add the Google Fonts

The app uses **Playfair Display** and **DM Sans** from Google Fonts. These need to be downloaded manually and placed in the fonts folder before you can build.

1. Go to **https://fonts.google.com**
2. Search for **"Playfair Display"** → click "Get font" → "Download all" → extract the ZIP
3. From the extracted folder, copy the `.ttf` files and rename them:
   - `PlayfairDisplay-Regular.ttf` → `playfair_display_regular.ttf`
   - `PlayfairDisplay-Bold.ttf` → `playfair_display_bold.ttf`
   - `PlayfairDisplay-Italic.ttf` → `playfair_display_italic.ttf`
4. Repeat for **"DM Sans"**:
   - `DMSans-Regular.ttf` → `dm_sans_regular.ttf`
   - `DMSans-Medium.ttf` → `dm_sans_medium.ttf`
   - `DMSans-Bold.ttf` → `dm_sans_bold.ttf`
5. Place all 6 files in:
   ```
   ChapterPlayer/shared/src/commonMain/composeResources/font/
   ```
6. Delete `FONTS_README.txt` from that folder.

---

## Step 3 — Add a Launcher Icon

Android Studio will create a default icon when you open the project.
After opening (Step 4), to create a proper icon:

1. Right-click `androidApp/src/main/res` in the Project panel
2. Choose **New → Image Asset**
3. Set "Icon Type" to **Launcher Icons (Adaptive and Legacy)**
4. Customize or keep the default — click **Next → Finish**

---

## Step 4 — Open the Project in Android Studio

1. Open Android Studio.
2. Click **"Open"** (or File → Open).
3. Navigate to the `ChapterPlayer` folder and click **OK**.
4. Android Studio will ask about the Gradle build — click **"Trust Project"**.
5. Wait for the **Gradle sync** to complete (bottom bar will say "Gradle sync finished").
   - This downloads all dependencies. First time takes 2–5 minutes depending on internet speed.
6. If you see errors about missing font files — complete Step 2 first, then sync again.

---

## Step 5 — Set Up a Way to Run the App

### Option A: Android Emulator (no phone needed)

1. In Android Studio, click **Tools → Device Manager**
2. Click **"Create Device"**
3. Choose a phone (e.g. Pixel 8) → click **Next**
4. Download **API 34** system image → click **Next → Finish**
5. Click the ▶ play button next to your new virtual device to start it

### Option B: Physical Android Phone

1. On your phone: go to **Settings → About Phone**
2. Tap **"Build Number"** 7 times to enable Developer Options
3. Go to **Settings → Developer Options → USB Debugging** → turn it ON
4. Plug your phone into your computer via USB
5. Accept the "Allow USB debugging?" prompt on your phone
6. Your phone should now appear in Android Studio's device selector

---

## Step 6 — Run the App

1. Select your device (emulator or phone) from the dropdown at the top of Android Studio
2. Click the **▶ Run** button (or press `Shift+F10`)
3. The app will build, install, and launch — takes about 30–60 seconds the first time
4. You'll see the Library screen (empty until you add a server)

---

## Step 7 — Connect Your Server

Once the app is running:

1. Tap **Settings** (bottom nav bar, rightmost icon)
2. Tap the **+** button (bottom right)
3. Select your server type: **ABS** or **Plex**
4. Fill in:
   - **Display Name**: anything you like (e.g. "Home ABS")
   - **Server URL**: the full URL including port (e.g. `http://192.168.1.50:8080`)
   - **Token**: your API token (see below)
   - **Library ID**: optional — leave blank for now (we can add library selection later)
5. Tap **Add Server**
6. Go back to **Library** and tap the **sync icon** (top right) to load your books

### Finding your ABS API token:
- In Audiobookshelf web UI → click your avatar → Settings → API Keys

### Finding your Plex token:
- https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/

---

## Known Limitations in This Version

- **Library ID is required for ABS** — after connecting, you need to find your library ID from the ABS API (`GET /api/libraries`) and enter it in the server settings. A library picker UI is coming in the next sprint.
- **Plex audiobooks** — works best when your Plex library type is "Music" (not "Other"). Narrator info isn't available from Plex metadata.
- **Player screen** — not built yet. Tapping a book navigates to a placeholder screen. The Player is next.
- **Font files** — must be manually downloaded (Step 2). The build will fail without them.

---

## Project Structure (Quick Reference)

```
ChapterPlayer/
├── androidApp/src/main/              ← MainActivity, application class, manifest, icons
├── shared/src/androidMain/kotlin/com/chapterplayer/
│   ├── data/
│   │   ├── model/          ← AudioBook, Chapter, ServerConfig, etc.
│   │   ├── local/          ← Room database (entities + DAOs)
│   │   ├── remote/         ← Retrofit API services (ABS + Plex)
│   │   └── repository/     ← BookRepository, ServerRepository
│   ├── di/                 ← Hilt dependency injection modules
│   ├── navigation/         ← AppNavigation, Screen routes
│   └── ui/
│       ├── components/     ← Shared Compose components
│       ├── library/        ← Library screen + ViewModel
│       ├── settings/       ← Settings + Server Setup screens
│       └── theme/          ← Colors, Typography, Theme
└── GETTING_STARTED.md      ← You are here
```

---

## What's Next

The project goals, stages and design direction live in **[docs/ROADMAP.md](docs/ROADMAP.md)**.
