# Signing and key custody — guidance for the client

Status: draft, to be handed over at the release gate
Date: 2026-07-20
Author: Security Engineer
Audience: **the client** — written assuming no prior Android release experience

This is the one part of the project where a mistake is expensive and hard to
undo. It is not technically difficult, but the consequences of getting it wrong
are permanent, so it is worth reading once slowly.

---

## The one thing to understand first

Android has no central authority that says "this app is really Squish". Instead,
every app is **signed** with a cryptographic key, and Android enforces one rule:

> An update can only be installed over an existing app if it is signed with the
> **same key** as the version already installed.

Two consequences follow, and they are the whole reason this document exists:

- **Lose the key → you can never update your app again.** Not "call support and
  recover it" — the update is cryptographically impossible. Your only option is
  to publish a brand new listing, with a new URL, zero installs, zero reviews
  and no way to migrate your existing players.
- **Someone else gets the key → they can ship malware as you**, and it installs
  over your app on your players' phones as a routine update, with no warning.

Everything below is about making the first outcome unlikely and the second one
hard.

## The hard rule for this project

**No keystore, key, password or Play Console credential ever enters the
development container.** The AI team builds **unsigned** artifacts. You sign
them, on your machine, or let Google sign them for you.

This is deliberate. It means that even in the worst case — the container is
compromised, a dependency is malicious, an agent misbehaves — nobody can produce
a signed build that Android will accept as an update to your app. It also means
the team never has to be trusted with the asset that matters. Please do not
relax this rule as a convenience during release week; that is exactly when it
will feel tempting.

## Two keys, not one

This is the part that confuses everyone the first time. Modern Google Play uses
**Play App Signing**, which involves two separate keys:

| | **App signing key** | **Upload key** |
| - | ------------------- | -------------- |
| What it does | Signs the app that players actually install | Signs what *you* send to Google |
| Who holds it | **Google**, in their key management service | **You** |
| If lost | Catastrophic — but if Google holds it, you cannot lose it | **Recoverable.** Google resets it for you |
| Who verifies it | Android, on every player's device | Google, on upload |

The flow: you sign your app bundle with your **upload key** and upload it.
Google checks the upload key, removes that signature, and re-signs the app with
the **app signing key** before distributing it.

The point of the arrangement is that the key you handle day to day — the upload
key — is the *recoverable* one. If your laptop dies or you lose the file, you
raise a support request, prove you are you, and register a new upload key. Your
app carries on.

Play App Signing is **required** for new apps on Google Play, so this is not
optional. It is also genuinely the safer arrangement.

## Recommendation: let Google generate the app signing key

When you enrol, Play offers a choice: let Google generate the app signing key,
or upload one you generated yourself.

**Let Google generate it.** For a first release with no existing app to be
compatible with, this is clearly right:

- The app signing key never exists outside Google's infrastructure, so it cannot
  be stolen from you, leaked in a backup, or lost.
- You never handle the one key whose loss is unrecoverable.
- The only key you are responsible for is the upload key — and that one is
  replaceable.

The alternative — generating the app signing key yourself and uploading it —
only makes sense if you already have a published app signed with an existing
key, or you need the same key across distribution channels. Neither applies
here. Choosing it would mean taking custody of an irreplaceable secret for no
benefit.

## Step by step

### 1. Register a Play Console account

- One-time registration fee (USD 25 at time of writing).
- Choose **personal** or **organization** at registration. This is awkward to
  change later, so decide deliberately — if the game might ever be published
  under a company, register the company now.
- You will go through identity verification. Organizations additionally need a
  D-U-N-S number, which can take days to obtain. **Start this early** — it is
  the slowest step and it is pure waiting.

> **Timeline warning, please check this yourself.** Google requires new
> *personal* developer accounts to run a closed test with a minimum number of
> testers (20 at time of writing) for a continuous period (14 days) before they
> can apply for production access. If that still applies, it adds **weeks**
> between "the build is ready" and "the game is on the store", and it needs real
> people opted into a test track. Verify the current requirement on the Play
> Console help pages as soon as you register, because it shapes the release
> schedule far more than anything the engineering team does. An organization
> account may not carry the same requirement — worth checking before you choose
> the account type above.

### 2. Generate your upload key

On **your own machine**, not in the container. You need a JDK installed for
`keytool`.

```
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -alias upload
```

- It asks for a **keystore password** and your name/organization details. The
  name details are not shown to users; they do not need to be perfect.
- `-validity 10000` is about 27 years. Play requires a key valid well beyond
  2033; do not shorten this.
- Use a **long random password from a password manager**, not something you
  invent. It protects the file if it ever leaks.

This produces one file: `upload-keystore.jks`. Treat it like the deed to the
app.

### 3. Store it properly

- **Password manager** (1Password, Bitwarden, similar) holding both the
  keystore *file* as an attachment and its password. This is the primary copy.
- **One offline backup** — an encrypted USB drive or similar, kept somewhere
  physically different. Cloud sync alone is not a backup; an account compromise
  or accidental deletion takes both copies at once.
- **Never** in the project repository, in a chat message, in an email, in a
  screenshot, or in the development container.
- Store the password **separately from the file** if you reasonably can.

The repository's `.gitignore` has been updated to ignore `*.jks`, `*.keystore`,
`keystore.properties` and `local.properties` so that an accidental `git add`
does not commit a key. That is a safety net, not permission to keep the key near
the project.

### 4. Enrol in Play App Signing and upload

Create the app in Play Console, opt into Play App Signing (choosing "let Google
generate the app signing key"), and upload your first signed app bundle. From
then on, every upload is signed with the same upload key.

### 5. Record the fingerprint

After enrolment, Play Console shows the SHA-256 fingerprint of both keys. Save
them somewhere you will find them again. They are how you verify, later, that a
build is genuinely yours — and they are not secret, so they can live in ordinary
notes.

## If something goes wrong

| Situation | What to do |
| --------- | ---------- |
| Lost the upload key or forgot its password | Recoverable. Generate a new one and request an upload key reset through Play Console support. Your app is unaffected. |
| Upload key possibly stolen | Request a reset immediately. The thief cannot ship to users with it — Google would still have to accept the upload — but revoke it anyway. |
| Play Console account compromised | This is the serious one. The account, not the key, is what controls what ships. Use a **strong unique password and hardware-backed or app-based 2FA on the Google account from day one**, before there is anything to protect. |
| App signing key compromised | Only possible if you chose to manage it yourself. If you followed the recommendation above, Google holds it and this is their problem to prevent. |

Note the ordering: **your Google account security matters more than the keystore
file**, because Play App Signing has moved the irreplaceable key to Google.
Whoever controls the Play Console account controls what reaches your players.
Enable 2FA before you do anything else.

## What the development team hands you at release

- An **unsigned** Android App Bundle (`.aab`), built reproducibly.
- The exact build command, so you or anyone else can reproduce it and confirm
  the artifact matches.
- Store listing text and assets.
- A completed Data Safety questionnaire draft — for Squish it declares **no data
  collected and no data shared**, which is true because the app has no network
  permission and cannot make network requests.

You perform the signing and the upload. The team never touches it.

## One caveat about verifying builds

Because Google re-signs your app under Play App Signing, the APK a player
downloads is **not byte-identical** to the bundle you uploaded. This is normal
and expected. If you ever want to verify a build independently, compare against
the artifact *before* upload, not against what the store serves. The team can
show you how to reproduce the build and check it matches.
