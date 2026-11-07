# Audible: what "support" could mean

_Research spike for Stage 6, 2026-11-07. No app code. Not legal advice._

**Hard rule, unchanged:** ChapterPlayer never removes DRM. It never decrypts, converts or plays Audible's protected files (AAX/AAXC), and never ships or calls code that does.

## 1. Official routes

- **No public API or SDK.** Audible has no developer programme and no documented API for third-party apps. Every library that reads an Audible library uses Audible's private app API, worked out by reverse engineering. The best-known Python client calls it "the non-publicly Audible API" ([mkb79/Audible](https://github.com/mkb79/Audible), read 2026-11-07). An Apple developer forum question about an official API had no answer we could see ([developer.apple.com/forums/thread/759763](https://developer.apple.com/forums/thread/759763)). The API directory listing that says the same is third party ([apievangelist](https://providers.apievangelist.com/providers/audible/)).
- **The terms.** The Conditions of Use (last updated 2026-11-02) forbid attempts to "circumvent any digital rights management or other protection system" and say Audible may end a membership at its discretion, with no refund when the user broke the terms ([audible.com/legal/conditions-of-use](https://www.audible.com/legal/conditions-of-use)). The License (last updated 2025-02-18) allows personal, non-commercial use only. It also forbids "data mining, robots, or similar data gathering and extraction tools" ([audible.com/legal/license-agreement](https://www.audible.com/legal/license-agreement)). The pages we read have no clause that names third-party player apps. Our reading: an unofficial client that signs in and pulls the library is at least close to "data gathering and extraction tools", and decryption is plainly forbidden. Removing DRM also breaks anti-circumvention law in many places (US 17 U.S.C. 1201; the UK and the EU have similar rules). We did not research the law for any one country.
- **Amazon integrations.** Audible plays on Alexa, Fire and Kindle devices and in Audible's own apps, which include Android Auto and CarPlay. All of these are Amazon's own products. We found no Alexa or Appstore route that lets another app play or list Audible titles. **Unverified:** whether the Amazon Creators API (which replaces the Product Advertising API, [webservices.amazon.com/paapi5](https://webservices.amazon.com/paapi5/documentation/use-cases/organization-of-items-on-amazon/search-index.html)) returns Audible catalogue items by ASIN. It needs an Amazon Associates account and has rules about how its data may be used and shown.
- **Audible Plus / Originals.** Plus Catalogue titles stay in the library only while the membership is active and the title is still in the catalogue. They download only inside the Audible app ([help.audible.ca, Plus Catalog](https://help.audible.ca/s/article/learn-about-the-plus-catalog)). Titles bought with cash or credits stay after cancelling. Nothing allows playing Plus titles outside Audible's apps, so the only option for them is a hand-off to the Audible app.

## 2. Unofficial routes in the wild (described, not adopted)

| Tool | What it does | Risk |
|---|---|---|
| python `audible` + `audible-cli` ([github](https://github.com/mkb79/Audible)) | Signs in through Amazon and registers a "virtual" device on the account (by default it poses as the Audible iPhone app). It then reads the library and, through audible-cli, downloads AAX/AAXC and gets activation bytes ([docs](https://audible.readthedocs.io/en/latest/auth/register.html)) | Private API that can change at any time. The fake device shows in the account's device list. The terms questions above |
| Libation ([github](https://github.com/rmcrackan/Libation)) | Desktop app that downloads the user's library and decrypts AAX/AAXC with AAXClean | Decryption: forbidden by the terms and, in many places, by law |
| OpenAudible | Desktop library manager. Converting to MP3 is a paid feature (per third-party reviews; not checked on its own site) | The same as Libation |
| Audiobookshelf's Audible metadata (Audnexus, [github](https://github.com/laxamentumtech/audnexus)) | Community server that serves catalogue metadata by ASIN (title, series, narrators, chapters). Its chapter endpoint needs Audible device keys | Not official. It needs no account from the person using it. GPL-3.0 |

This is how Audiobookshelf users get their purchases in: they run Libation or OpenAudible on a desktop, drop the decrypted files into an ABS library folder (one folder per book, with the ASIN in brackets in the folder name to help matching, per [ABS docs](https://docs.audiobookshelf.org/docs/documentation/libraries/book-library/directory-structure)), and ABS fills in metadata through Audnexus. **ChapterPlayer will not build, bundle, call or link to any of the decryption tools.** What owners do with their own files on their own server is their own business. The app plays whatever ABS or Plex serves, as it does today.

We found no official record of accounts banned for using these tools. Forum posts say conversion breaks the terms. That the risk is low does not mean it has been verified.

## 3. Safe options that fit the project

**(a) Hand-off to the Audible app.** Verified by reading Audible's published association files on 2026-11-07:
- Android: `https://www.audible.com/.well-known/assetlinks.json` names `com.audible.application` (and `.samsung` and `com.audible.universal`) with `handle_all_urls`. An `https://www.audible.com/pd/<ASIN>` link will therefore open the Audible app when it is installed, else the browser. **Unverified:** which paths the app's manifest actually claims. Check on the phone with `adb shell pm get-app-links com.audible.application`.
- iOS: `apple-app-site-association` lists the app `com.audible.iphone` for `/pd/<ASIN>`, `/pd/*/<ASIN>`, `/author/...` and `/webplayer?asin=<ASIN>`. We found no documented custom URL scheme (`audible://`), so use only https links.
- The catch is that a list to hand off from needs a source. Without the private API the app cannot read the owner's Audible library. That leaves (b), or ASINs already in ABS metadata.

**(b) Official data export.** Amazon's Request My Data page has an Audible option ([help.audible.co.uk](https://help.audible.co.uk/s/article/manage-your-personal-data)). Third-party write-ups say the result is a zip of CSV files, including an Audible library file ([amalytix](https://amalytix.com/en/blog/amazon-data)). **Unverified:** the exact files and columns, whether listening history or positions are included, and how long it takes (reports range from days to weeks). It would be a one-off, manual, read-only import: a list of owned titles and ASINs, and perhaps "finished" dates. It is no live sync.

**(c) Make the existing path smoother.** The owner's own files already play from ABS and Plex.
- Read the ASIN that ABS already stores (from folder names or Audnexus matches) and use it as a stronger key in `mergeDuplicates`, on top of title and author.
- Add an "Open in Audible" action on books that have an ASIN (the hand-off link from (a)). This is useful for Plus titles, or when the owner wants Audible's own app.
- An "Also on Audible" badge would need to know the owner's Audible library (only (b) gives that) or a public catalogue source. The only official catalogue route is the Creators API (unverified, needs an Associates account). Audnexus is community-run. We would skip the badge unless (b) works.

**(d) Do nothing.** Audible stays out of scope. Owners keep using the Audible app.

## 4. Recommendation

Do **(c)** first, in a small form: an ASIN-aware merge and an "Open in Audible" link for books whose ABS metadata carries an ASIN (**S**, about 1 to 2 days, no new sign-in, no Amazon contact). Then, only if the owner wants it and once a real export has been looked at, do **(b)** as a one-off import of a CSV into a read-only "Audible" shelf whose rows hand off to the app (**M**). Do not build anything on the private API (**L**, it breaks over time and has terms and account risk). Never decrypt.

| Option | Pros | Cons / risks | Size |
|---|---|---|---|
| (a)+(c) hand-off and ASIN merge | No account access. Uses only data the owner's server already has, plus public https links | Only books already on ABS have an ASIN. Plex usually exposes none (unverified for Plex's Audnexus agent). App Link paths still to check | S |
| (b) export import | Official, read-only, the owner's own data | Manual and slow. Format unknown and may change. No progress sync | M |
| Private API library view | A live library list | Reverse-engineered. Registers a fake device. Near the "data gathering" ban. Account-flag risk. Breaks without warning | L |
| (d) nothing | No risk, no work | No Audible presence at all | none |

## Questions for the owner before any build

1. Does a hand-off to the Audible app count as "Audible support" for you, or do you only want Audible books that play inside ChapterPlayer (which, without DRM removal, means files you already host on ABS or Plex)?
2. Do you keep Plus Catalogue titles, or only purchases? Plus titles can only ever be handed off.
3. Will you file one Amazon "Request My Data" request for Audible, so we can see the real files before designing (b)?
4. Which Audible marketplace (.com, .co.uk, ...)? The links and the export differ by region.
5. Is an ASIN shown in ABS for your Audible-sourced books today? (Check one book's details.)
6. Are you comfortable with any request to Amazon from the app beyond opening a link? We assume not.
