# FX Calculator - Android App

A USDRUB / USDT currency conversion calculator for Android.

## Features

- **USDRUB rate inputs** with bidirectional percentage calculation
- **USDT conversion** with two cost components
- **Reverse-solve mode** — enter target USDRUB client rate, auto-solve for USDRUB MU
- **Save/Load** — save calculation snapshots to SQLite, recall them later
- **Dark theme** optimized for mobile
- **Auto-select** — tap any input to select all text for quick editing

## How to build the APK (step by step)

### 1. Create a GitHub account
- Go to https://github.com and sign up (free)

### 2. Create a new repository
- Click the **+** button (top right) → **New repository**
- Name it `fx-calculator` (or anything you like)
- Choose **Public** (free) or **Private**
- Do NOT check "Add a README file" (we already have one)
- Click **Create repository**

### 3. Upload the project files
- On your new repository page, click **"uploading an existing file"** link
- Drag and drop ALL files and folders from this zip into the upload area:
  - `.github/` folder
  - `app/` folder
  - `gradle/` folder
  - `build.gradle`
  - `settings.gradle`
  - `gradle.properties`
  - `gradlew`
  - `gradlew.bat`
  - `README.md`
- Click **Commit changes**

**Important:** Make sure the folder structure is preserved. GitHub's uploader
should handle this if you drag the folders in.

**Tip:** If the drag-and-drop doesn't preserve folders, use GitHub Desktop
(free app) or the `git` command line instead:
```bash
cd fx-calculator
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR_USERNAME/fx-calculator.git
git push -u origin main
```

### 4. Wait for the build
- Go to the **Actions** tab in your repository
- You should see a workflow running called "Build APK"
- Wait 3-5 minutes for it to finish (green checkmark)

### 5. Download the APK
- Click on the completed workflow run
- Scroll down to **Artifacts**
- Click **FX-Calculator-APK** to download the zip
- Extract the zip — inside you'll find `app-debug.apk`

### 6. Install on your phone
- Transfer `app-debug.apk` to your Android phone
- Open it with your file manager
- Allow "Install from unknown sources" if prompted
- Install and open the app

## How to use

### Normal mode
- Enter USDRUB rates, RUB amount, and USDT costs
- All calculated fields update in real time
- Tap any input — text auto-selects for quick replacement

### Solve mode
- Tap **SOLVE** button in the top bar
- The USDRUB client field becomes an input (green border)
- Enter your target client rate
- USDRUB MU auto-calculates to match (shown as "solved")
- Tap **SOLVE** again to exit

### Saving calculations
- Tap **SAVE** button in the top bar
- Enter a name (optional — defaults to current date/time)
- Tap **Save**
- Tap any saved entry to restore it
- Tap **DEL** to remove a saved entry
