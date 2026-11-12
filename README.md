# Photo Cleaner

## [⬇ Download the latest APK](release/PhotoCleaner.apk?raw=true)

CI rebuilds `release/PhotoCleaner.apk` on every code push, so the link always points to the newest build.

A duplicate photo and video cleaner for **Android 16 and 17** (minSdk 36, targetSdk 37).
**You choose the folders it searches.** In them it finds:

- **identical copies** of photos and videos, byte for byte;
- **look-alike photos**: copies that were resized, saved again, turned, mirrored or cropped;
- **burst shots**: photos taken a moment apart, of which it keeps the sharpest;
- **blurry and dark photos**, listed on their own.

For each group it suggests the copy to keep and ticks the others. Ticked files go to Android's
trash, where they stay for 30 days, and Undo brings them straight back.

It only ticks what it is sure of. An edit, a crop and a RAW file's JPEG twin are versions of their
own: the best copy of each is kept, and only its own duplicates are ticked. A favourite, or a
picture that merely has the same layout as another (two pages of text, two screens of the same
app), is never ticked for you.

Lean: Kotlin on the Android framework, **no libraries**, **no internet permission**, an APK of about 210 KB.
It works fully offline, and nothing leaves the phone. It reads your photos on **every processor
core** at once, and it is built not to fall over (see [Built to last](#built-to-last)).

<p>
<img src="docs/screenshots/1-folders.png" width="240" alt="The folder list with tick boxes">
<img src="docs/screenshots/3-results.png" width="240" alt="Groups of duplicates, the copy to keep marked">
<img src="docs/screenshots/4-viewer.png" width="240" alt="A photo at full size, to compare">
</p>

*Screenshots are taken by CI on an Android 16 emulator on every build.*

## Choosing the folders to search

The start screen lists every folder on the phone that holds photos or videos, as a tree under
**Phone** (and **SD card**, if there is one), each with its number of photos and videos and their size.

- **Tick a folder to search it.** Ticking a folder ticks everything below it. Open a folder with
  its arrow to tick or untick its subfolders one by one. A half-filled box means only some of a
  folder's subfolders are ticked.
- **All** and **None** tick or clear every folder at once.
- Chains of folders with nothing of their own are shown as one row, such as
  `Android/media/com.whatsapp/WhatsApp/Media`.
- **Your choice is remembered.** A folder that appears later is ticked automatically only if every
  folder was ticked; otherwise it shows a **New** badge and waits for you.
- **Only the ticked folders are read.** For example, tick `DCIM/Camera` and `WhatsApp Images` to
  find the photos you took that came back to you in a chat.

The bar at the bottom shows how many folders, photos and videos the scan will read.

## Using it

1. **Allow access** to photos and videos. Choose *Allow all*: with *Select photos* the app can only
   compare the photos you picked, and it says so.
2. **Tick the folders** to search. The tune button at the top chooses what to look for: photos,
   videos, look-alikes, bursts, blurry and dark photos, and how alike look-alikes must be (strict,
   normal or loose).
3. **Scan.** The first scan reads every photo, which takes a few minutes for thousands of them. It
   remembers what it read, so later scans only read new photos. You can leave the app meanwhile:
   the scan carries on, and its notification shows how far it is.
4. **Review.** Every group has the copy to keep marked **Keep**; a red tick means a file goes to the
   trash. Tap a picture to see it at full size: pinch to zoom, swipe to the other copies, and read
   why it was ticked or kept. Tap the circle on a picture to tick or untick it. **Keep best** ticks
   the suggested files of a group again; **Keep all** unticks the group.
5. **Move to trash.** Android asks you to confirm, then the files go to its trash. **Undo** brings
   them back; so does the trash in the Files or Photos app, for 30 days.

## How it decides

The fingerprints, cut-offs and keep rules start from DupeFinder's, the desktop duplicate finder.

| Group | Found when | Suggested to keep |
|---|---|---|
| Identical copies | the files have the same SHA-256 (compared only when they have the same size) | one without a copy name such as "(1)" or " - Copy", then the copy in a camera, screenshots or screen-recordings folder, then the first added |
| Look-alike photos | their 64-bit perceptual hashes (pHash) differ in at most 8 bits (strict 4, loose 12), also against the other photo turned, mirrored or cropped to its centre, **and** their fine fingerprints (below) differ in at most 48 of 255 bits (strict 16), unless the two clearly differ in colour | the one with the most pixels, then the largest file |
| Burst shots | photos from the same camera (EXIF make and model), at most 3 seconds apart, whose hashes differ in at most 16 bits and whose fine fingerprints differ in at most 56 | the sharpest (Laplacian detail, halved for a dark or clipped shot) |
| Blurry or dark photos | a photo from a camera (its EXIF data names the camera or the moment) with sharpness below 45 of 100 (a re-blur measure of the 4 sharpest of 16 tiles), median brightness below 40, or more than 25 % of pixels near black or white | never ticked: decide yourself |

**The fine fingerprint.** A 64-bit pHash only sees a picture's layout. Measured on generated test
pictures, 1 pair in 100 of *different* text pages is within 8 bits, and 3 in 10 are within the 16
bits of a burst, so pages of a document photographed one after the other would count as a burst and
all but one be ticked. The app therefore also compares the signs of the 16 x 16 lowest frequencies
of the same transform (255 bits). Real copies (resized, saved again) differ in under 16 of them;
different pages and screens that share their layout in over 55.

**Versions.** Within a group, files that are surely the same picture, unedited, form a version. A
mirrored or cropped copy, one whose brightness, contrast or colours differ by more than saving or
resizing changes them (a filter, black and white, a brighter version) and a RAW file's JPEG twin are
versions of their own: someone made them on purpose. The best copy of every version is kept, and
every other file is compared with the kept copy of its own version. So an edit's duplicates go while
the edit stays, and the original's duplicates go even when the edit is the larger file. A camera
that saves every shot as a RAW file and a JPEG gives two bursts, and the sharpest of each is kept.

**What is ticked for you** is only what is sure:

- identical copies;
- look-alikes within 8 pHash bits and 32 fine bits of the kept copy of their version ("Loose" finds
  more, but ticks no more than "Normal");
- burst shots that look like the kept shot (a different moment of a long burst is left alone).

**Never ticked for you:**

- the best copy of any version: the original, an edit, a crop, the RAW file, its JPEG;
- favourites;
- a file that only matches another member of its group, not the copy kept;
- a file you unticked before: what you keep by hand stays kept in every later scan.

When every copy of a picture, or of one of its versions, is ticked, the app asks before moving them
all. The last copy left of anything is never ticked where you can't see it: when the kept copy of a
version has gone (deleted in another app, say), the last copy left of it is unticked.

## Every core

Scanning is spread over all the processor's cores, the way the LINPACK benchmark app and Lean
Reader do it:

- **One reading thread per core**, as far as free memory allows (Lean Reader's rule: threads =
  min(cores, free memory / memory per thread)). The scan screen says how many are working.
- **Files are handed out one at a time** from a shared counter, so on a phone with fast and slow
  cores (big.LITTLE) the fast ones take more and every core keeps working until the last file.
- **Each file is opened once.** Its fingerprint, its capture time and its pixels all come from that
  one open, where a simple reader asks Android four times per photo. With the photos' own bytes
  allowed (see [Privacy](#privacy)), Android also hands out the file itself: it doesn't search every
  photo for location data first, and reads don't pass through its filter.
- **Each thread keeps its own buffers** (the grey copy, the noise filter's output, a read buffer and
  its SHA-256 state), so reading a photo allocates almost nothing and the garbage collector doesn't
  stop the threads. Photos are fingerprinted as they stream in and decoded straight to 1024 px, in
  sRGB: no file is ever held in memory whole.
- **Decoders share a memory budget.** JPEG, PNG and WebP are decoded reduced. HEIC and AVIF are
  decoded whole first and a RAW file is developed whole, so a 50-megapixel HEIC takes 300 MB for a
  moment: those wait for each other when memory is short, while small photos keep every core busy.
- **Comparing** every pair of photos runs on all cores too, in chunks of equal work handed out the
  same way, and shows how far it is.
- The threads run at normal priority, a notch below the screen, so the phone stays smooth and the
  threads aren't kept to the slow cores the way background work can be. The processor is kept awake
  while a scan runs, so it doesn't stall when the screen goes off.

## Built to last

- **A file that fails never stops a scan.** A photo that can't be decoded, a truncated file, a text
  file with a photo's name: each is reported and skipped. Files that ran out of memory, couldn't be
  read or couldn't be decoded get a second try at the end, one at a time, in case the crowd was the
  cause (phones have few hardware decoders for HEIC photos).
- **A file that hangs is given up** after a minute (plus a second per 10 MB), and a spare thread
  takes the place of the one it holds. If the storage stops answering altogether (a failing SD
  card), the rest is given up too: a scan always ends.
- **A photo that makes the app die is found and left out.** A crash inside a picture decoder can't
  be caught: the app is gone. The app notes which files it is reading in a small journal. After such
  a death, the next scan reads the files that were in flight one at a time; a file that kills the
  app again, on its own, is left out from then on. One bad photo can stop a scan twice, never a
  third time. The app's own closing (by you, or by Android to free memory) is told from a crash and
  blames no file.
- **When the phone gets hot**, the scan rests half its threads (one when critical) until it cools
  down, instead of making Android throttle the whole phone.
- **Nothing is lost when Android closes the app.** What has been read is saved as the scan goes, so
  a stopped scan resumes where it was. If the app is closed while its results are open, they come
  back when you return, with your ticks.
- **Files that changed meanwhile are checked.** Before moving files to the trash, and after Android
  answers, the app checks which files are really there, in every group that has a tick. It never
  acts on or reports a file another app has deleted, and if a deleted file leaves a ticked copy as
  the last one, that copy is unticked. The folder list updates by itself when photos arrive.
- **A damaged cache is rebuilt**, and a full storage only means fingerprints aren't kept this time.
  The folder choice for an SD card is kept while the card is out.

## Privacy

- **No internet access.** The app doesn't have the permission, so it can't send anything anywhere.
- **What it asks for:** your photos and videos; notifications, for a scan's progress; to keep the
  processor awake during a scan; and the photos' own bytes (Android's "media location" permission,
  part of the same question as the photos). Without the last one Android hands out copies with the
  place a photo was taken blanked out, so "identical" would not quite mean byte for byte. The app
  doesn't read or show where a photo was taken.
- **The fingerprint cache stays on the phone**, in the app's private storage, and isn't backed up.
- **Nothing is deleted for good** by the app: files go to Android's trash, which empties itself
  after 30 days.

## Build

JDK 17+ and Android SDK 37: `./gradlew assembleRelease`. The **Build APK** workflow runs on every
code push. It runs the unit tests and lint, builds the minified APK, commits it to `release/`, then
runs the on-device test on Android 16 and 17 emulators.

All builds are signed with `app/photo-cleaner.keystore`, so updates install over each other and keep
your folder choice and fingerprint cache. The repository is public, so anyone can sign an APK with
this key: it is only fit for sideloading your own builds. Use a private key for any store release.

## Tests

| What | How |
|---|---|
| JVM tests (69): perceptual hashes and fine fingerprints of synthetic photos (resized, saved again, turned, mirrored, cropped, grey), blur, noise, exposure, brightness and contrast measures, the median filter against a naive one, reused per-thread buffers giving the same answers as fresh ones; every grouping and keep rule (identical, look-alike, turned, mirrored, cropped, edited, RAW twins, versions that each keep their best copy, linked through another file, bursts, RAW and JPEG bursts, their copies and far frames, colour, favourites, flat pictures, videos, poor shots, matching on many threads, stopping and progress); 60 different text pages and 40 app screens that a pHash confuses must never be grouped, nor pages photographed one after the other be a burst; the thread count, time limits, heat rules, the decoders' memory budget (also with a decoder that hangs) and the crash journal; what is ticked in the results, what is remembered, and that the last copy of a picture or of a version never stays ticked; the folder tree, tick boxes and how new folders are treated; copy names and sizes | `./gradlew testDebugUnitTest` |
| On-device test on Android 16 and 17 emulators: copies test photos to the phone (`scripts/make_test_photos.py`), including five document pages photographed 2 s apart and two settings screens that a pHash takes for a burst and for look-alikes, an edited version with a copy of its own, a truncated photo, a text file with a photo's name and an 8000 px panorama; scans three different sets of folders and checks every group found, what is ticked and what is left alone, and the broken files reported; moves the ticked files to the trash through the real button and Android's own dialog, checks the media index, undoes it; has Android close the app while its results are open and checks they come back; with a test build that dies on purpose while reading one photo, checks that the photo is left out by the third scan; rotates, and runs 15,000 random GUI events (monkey); fails on any wrong result, crash or ANR | `scripts/device-test.sh` (CI) |
| Static analysis: Android lint | `./gradlew lintDebug` |

## Code

| File | What it does |
|---|---|
| `core/ImageAnalysis.kt` | pHash with its turned, mirrored and centre-crop variants, the fine fingerprint, area resizing, sharpness (re-blur measure, Laplacian tiles, noise estimate, median filter), exposure, brightness and contrast |
| `core/Matcher.kt` | links identical files, look-alikes and burst frames, forms groups, splits them into versions (edits, crops, RAW twins), picks the copy to keep of each, and decides what to tick |
| `core/FolderTree.kt` | the folder tree, its tick boxes, and the selection kept between runs |
| `core/Names.kt` | copy names, original folders, sizes and counts |
| `core/Parallelism.kt` | how many threads a scan runs, how long a file may take, how many threads rest when the phone is hot, and the memory budget decoders share |
| `core/Journal.kt` | the note of files being read, which finds a photo that makes the app die |
| `media/MediaIndex.kt` | reads photos, videos and storage volumes from Android's media index |
| `media/Analyzer.kt` | opens a file once and reads its SHA-256, EXIF capture time and camera, and its pixels reduced to 1024 px |
| `media/FeatureCache.kt` | the fingerprint cache (SQLite), so later scans only read new files |
| `media/Thumbs.kt` | thumbnails and full-size pictures, off the main thread |
| `scan/Scanner.kt` | a scan of the ticked folders: the threads on every core, the watchdog and its spare threads, the second tries, the heat limit, the crash quarantine |
| `scan/ScanService.kt`, `scan/ScanState.kt` | the scan as a foreground service with a notification and a wake lock, and its results and ticks |
| `ui/FoldersScreen.kt` | the folder list with tick boxes, the options and the about box |
| `ui/ScanScreen.kt`, `ui/ResultsScreen.kt`, `ui/ViewerScreen.kt` | progress, the groups found, and one file at full size |
| `MainActivity.kt` | screens, Back, permissions, and Android's trash confirmation |
