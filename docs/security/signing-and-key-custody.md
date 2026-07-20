# Signing, key custody and sideloading — guidance for the client

Status: revised 2026-07-20 for **direct distribution** (no Play Store this phase)
Supersedes: the Play App Signing guidance in the first draft of this document
Author: Security Engineer
Audience: **the client** — written assuming no prior Android release experience

The client has decided to skip the Play Store for this phase. We build to
store-ready standard and hand over a **signed APK** that you share directly and
recipients sideload. Publishing is deferred, not cancelled.

That decision changes this document substantially, and not in your favour on one
specific point. Please read §2 carefully — it is the one thing in this project
that cannot be undone.

---

## 1. What signing is, in one paragraph

Android has no central authority that says "this app is really Squish". Every
app is **signed** with a cryptographic key, and Android enforces one rule:

> An update can only be installed over an existing app if it is signed with the
> **same key** as the version already installed.

That rule is what makes an app's identity meaningful. It is also what makes the
key irreplaceable.

## 2. The one unrecoverable failure mode — read this twice

**If you lose the keystore file, or forget its password, you can never update
Squish again.**

Not "difficult". Not "contact support". Cryptographically impossible. Android
will refuse the install, and there is no appeal, because there is no longer
anyone in a position to grant one.

This is a *change* from the earlier plan. Google Play offers "Play App Signing",
where Google holds the real signing key and you hold a disposable *upload* key
that they will reset for you if you lose it. That safety net turned key loss
into a support ticket. **Without a Play Console account, that safety net does
not exist.** You hold the only copy of the only key that matters.

What happens if the key is lost, concretely:

- Existing players keep the version they have. It keeps working.
- You cannot ship them a fix or an update, ever.
- To distribute anything further you must sign with a new key, which Android
  treats as a **different app**. Recipients must **uninstall first**, then
  install the new one.
- Uninstalling deletes app data. Because we deliberately disabled Android's
  cloud backup for privacy reasons (see the threat model, finding S-1), there is
  **no backup to restore from**. Every player's high score is gone permanently.

Those two decisions interact, and you should know we made the trade knowingly:
no backup means no leak, and it also means no recovery. For a local arcade score
that is the right call — but it does mean key loss costs your players something
real, not just you.

> **The realistic risk here is loss, not theft.** You are one person, not a
> target. Nobody is trying to steal this key. You are far more likely to lose it
> to a dead laptop, a forgotten password, or a tidy-up in three years than to an
> attacker. **So the guidance below biases toward redundancy over secrecy** —
> more copies, in more places. That is the opposite of normal secret-handling
> advice, and it is deliberate.

## 3. One key now, not two

Under the earlier Play plan there were two keys. Now there is one.

| | **Your signing key** |
| - | -------------------- |
| What it does | Signs the APK that people install |
| Who holds it | **You. Only you.** |
| If lost | **Unrecoverable.** See §2 |
| If stolen | Someone can sign an app that installs over yours as an update |

Simpler, and more fragile. There is no reset path and no second chance.

## 4. The hard rule for this project

**No keystore, key, password or credential ever enters the development
container.** The AI team builds an **unsigned** APK. You sign it, on your own
machine.

Even in the worst case — the container compromised, a dependency malicious, an
agent misbehaving — nobody can produce a build that installs as an update to
your app. Please do not relax this during release week as a convenience. That is
exactly when it will feel reasonable.

## 5. Generate your key

On **your own machine**. You need a JDK for `keytool` — it comes bundled with
Android Studio if you would rather not install one separately.

```
keytool -genkeypair -v \
  -keystore squish-release.jks \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -alias squish
```

- It asks for a **keystore password**, then for your name and organization
  details. Those details go into the certificate but are not shown to users;
  they do not need to be perfect. You cannot change them later without changing
  the key, so put something you are happy to live with.
- `-validity 10000` is about 27 years. Do not shorten it. A key that expires is
  a key you can no longer update with.
- **Use a long random password from a password manager**, not one you invent and
  hope to remember. You will not remember it. It belongs in the backup record
  below, and that is where it is meant to live.

Write down these three things immediately — **you need all three, and losing any
one loses the app**:

1. The **file** `squish-release.jks`
2. The **keystore password**
3. The **alias** (`squish`, above)

Then record the certificate fingerprint, which is not secret and which you will
want for §8 and §9:

```
keytool -list -v -keystore squish-release.jks -alias squish
```

Copy the **SHA-256 fingerprint** line into your notes.

## 6. Back it up — and verify the backup actually works

This is the section people skim, and it is the one that decides whether §2 ever
happens to you. Please do these in order rather than deciding to get to it after
release.

### 6.1 Make three copies, in at least two places

| # | Where | What goes there |
| - | ----- | --------------- |
| 1 | **Password manager** (1Password, Bitwarden, similar) — attach the `.jks` file to an entry | file + password + alias + fingerprint |
| 2 | **Encrypted USB drive** kept at home | file + a text file with password, alias, fingerprint |
| 3 | **A physically different location** — a USB at a relative's house, a safe deposit box, or a sealed envelope with someone you trust | same as #2 |

Why three: a password-manager lockout, a drive failure and a house fire are
independent events. Two copies in the same building is one copy.

Why the password travels *with* the file in copies 2 and 3: standard advice says
separate them, but that advice assumes theft is the risk. Here loss is the risk,
and a backup you cannot open is not a backup. Keep them together and make the
place itself secure.

### 6.2 Never put it in any of these

- The project repository (the `.gitignore` now blocks the common filenames, but
  do not rely on that)
- The development container
- Email, chat, or a screenshot
- An unencrypted cloud folder that syncs automatically — sync propagates
  deletion, so it is not a backup

### 6.3 Verify the backup — the step that is usually skipped

A backup you have never opened is a guess. Test the **copy**, not the original,
because what you need to know is whether the copy is usable.

1. Copy the `.jks` file **from backup #2** into a temporary folder — ideally on a
   different computer than the one you created it on.
2. Look up the password **from the backup record**, not from memory and not from
   the original.
3. Run:

   ```
   keytool -list -v -keystore /path/to/restored-copy.jks -alias squish
   ```

4. **Success** looks like: it accepts the password and prints certificate details
   including a SHA-256 fingerprint. Check that fingerprint matches the one you
   recorded in §5.
5. **Failure** looks like: "keystore password was incorrect", "Alias does not
   exist", or a file error. Any of these means that backup is not usable — fix it
   now, while the original still exists.
6. Delete the temporary copy.

Do this **when you create the backups**, and again **once a year**. Put the
annual check in your calendar now. It takes two minutes and it is the only thing
standing between you and §2.

### 6.4 A note on key rotation, since you may read about it

Android's v3 signature scheme supports rotating to a new key. It is **not a
recovery path** — rotation requires signing with the *old* key to prove
continuity. If the old key is gone there is nothing to rotate from. Rotation
helps if a key is *compromised* and you still have it. It does nothing if it is
lost. Do not file it mentally as a safety net.

## 7. Sign the APK

The team gives you an unsigned APK. Two routes; pick whichever you find less
unpleasant.

### Route A — Android Studio (recommended if you are not comfortable in a terminal)

**Build → Generate Signed App Bundle / APK → APK**, choose your keystore file,
enter the password and alias, select the **release** variant, and ensure
signature versions **V2** and **V3** are checked. It produces the signed APK.

### Route B — command line

`apksigner` ships with the Android SDK build-tools.

```
apksigner sign \
  --ks squish-release.jks \
  --ks-key-alias squish \
  --out squish-1.0-signed.apk \
  squish-1.0-unsigned.apk
```

It prompts for the password. **Do not pass the password as a command-line
argument** — it lands in your shell history.

Squish targets Android 10 (API 29) and above, so the modern v2/v3 signature
schemes apply and legacy v1 JAR signing is unnecessary. The defaults are
correct; you do not need extra flags.

## 8. Verify what you signed

Always, before sharing:

```
apksigner verify --print-certs squish-1.0-signed.apk
```

Confirm it reports the signature as verified, and that the **SHA-256 digest of
the certificate matches the fingerprint you recorded in §5**. If it does not
match, you signed with the wrong key — stop and find out which one before it
reaches anybody.

## 9. Sideloading — what your users will see

Because the app does not come from the Play Store, everyone you share it with
hits a warning. Worth understanding so you can explain it honestly.

### What actually happens

The recipient downloads the APK and taps it. Android says something close to:

> *"For your security, your phone is not allowed to install unknown apps from
> this source."*

They must grant permission to **the app doing the installing** — Chrome, Files,
Drive, WhatsApp — not flip a global switch. This is per-source and per-app, and
has been since Android 8. They may also see **Play Protect** offer to scan the
app, which is normal and worth accepting.

### What the warning means, accurately

It does **not** mean Android thinks the app is malicious. It means Android
cannot vouch for it, because it did not come from a store that reviewed it.
Android is correctly saying *"I don't know what this is — do you trust where you
got it?"*

That is a real and sensible protection, not a formality to click past. The
honest framing: **the warning is about the source, not the file.** They are
being asked whether they trust you.

### What to tell people you share it with

Something like:

> This is a game I had built. It isn't on the Play Store yet, so your phone will
> warn you before installing — that warning is Android saying it can't verify
> apps from outside the store, which is normal for a direct download. It has no
> internet access at all, so nothing can leave your phone. The only thing it
> asks for is permission to vibrate, for the haptic feedback when blocks land.
> If you'd rather wait until it's on the Play Store, that's completely
> reasonable.

Accurate, not pushy. The last sentence matters — some people should not
sideload, and they should feel fine saying no.

**Do not say "it asks for no permissions."** That was true before impact haptics
and is now false. Say "no internet access" — which is the claim that actually
carries the privacy weight, and which is still exactly true.

### What a recipient can reasonably check

If someone technically-minded asks how to verify it:

- **Permissions.** The app requests **exactly one**:
  `android.permission.VIBRATE`, needed to drive the impact haptics at varying
  strength. Nothing else — and specifically **not** `android.permission.INTERNET`.

  Two things to be straight about, because they are easy to get wrong:

  - **The install screen will probably show no permissions at all**, and that is
    *not* evidence that none are requested. Android only surfaces "dangerous"
    permissions — camera, location, contacts, files — because those are the ones
    that reach your data. `VIBRATE` is classed as **normal**: it is granted
    automatically at install, shows no prompt, and grants access to no data
    whatsoever. The worst a normal permission can do is buzz the phone.
  - **Seeing the full list needs a tool.** `apksigner`'s sibling `aapt2 dump
    permissions squish.apk`, or any APK inspector app, prints every permission
    including the normal ones. That is where a sceptical recipient should look,
    and what they should see is one line: `VIBRATE`.

  **The claim worth making is "no internet access", not "no permissions."** The
  absence of `INTERNET` is the one that carries the privacy guarantee, it is
  kernel-enforced (the app physically cannot open a network connection), and it
  is still exactly true. It is also independently verifiable by the same tool.
- **The certificate fingerprint.** Publish your SHA-256 fingerprint (§5)
  somewhere stable and they can compare with `apksigner verify --print-certs`.
  Be honest about the limit: this only helps if they got the expected
  fingerprint through a *different* channel than the APK. If an attacker
  replaced both, they match perfectly and prove nothing. Its real value is
  confirming a *later* APK came from the same person as the first.
- **The source.** Ultimately this is the actual control. An APK from a link you
  personally sent is exactly as trustworthy as you are, and that is the entire
  security model of direct distribution. Say so plainly rather than implying
  more.

Suggest people **turn the "install unknown apps" permission back off** for
whichever app they used, once they are done.

## 10. Updates

Without a store there is no automatic update. To ship a new version:

- Sign it with the **same key** (§2 again).
- Increment `versionCode` — Android refuses to install over a newer version.
- Send the new APK out. Recipients install over the existing app and their data
  is kept, because the signature matches.

There is no way to reach people who miss your message, and no way to pull a bad
build back. Test before you send.

## 11. If something goes wrong

| Situation | What to do |
| --------- | ---------- |
| Lost the keystore, but you have a backup | Restore and verify with §6.3. This is why the backups exist. |
| Lost the keystore and all backups | Unrecoverable. See §2. New key, new identity, recipients uninstall and reinstall, scores lost. |
| Forgot the password, still have the file | Effectively the same as losing it. There is no password recovery for a keystore. |
| Keystore leaked or possibly stolen | Serious but not fatal. Generate a new key and rotate (§6.4) — you still hold the old key, so continuity works. Do it promptly. |
| Not sure which key an APK was signed with | `apksigner verify --print-certs` and compare fingerprints (§8). |

## 12. What the team hands you

- An **unsigned** release APK, built reproducibly, plus the exact build command
  so anyone can rebuild and confirm it matches.
- This document.

You perform the signing. The team never touches the key.

**One upside of leaving the Play Store:** because Google is not re-signing your
app, the APK a user installs is **byte-for-byte the artifact we built and you
signed**. Under Play App Signing that would not have been true. Reproducibility
now extends all the way to the user's device, so an independent party can
rebuild from source and confirm nothing was inserted. That is a genuinely
stronger position than the Play route offered, and it is worth mentioning if
anyone asks why they should trust a sideloaded APK.

## 13. Deferred — Google Play

Not in scope this phase. If the client later decides to publish, revisit before
doing anything:

- **The key decision at enrolment.** Play App Signing will offer you the choice
  of uploading your existing key or letting Google generate a new one. Uploading
  the existing key keeps continuity, so people who sideloaded can update from
  the Play version. Letting Google generate a new one is safer going forward but
  makes the Play app a separate identity — sideloaded users would have to
  uninstall and reinstall, losing their scores. **That trade-off deserves a
  deliberate decision, not a default accepted in a setup wizard.**
- **Account lead time is the long pole.** Personal developer accounts created
  after 13 Nov 2023 must run a closed test with **12 testers for 14 consecutive
  days** before applying for production access. Organization accounts are exempt
  but need a D-U-N-S number, which takes days to obtain. Decide the account type
  deliberately; it is awkward to change later.
  Source: https://support.google.com/googleplay/android-developer/answer/14151465
- **Data Safety declaration** must be completed — see `threat-model.md` §10 for
  what must be true before it can be truthfully filled in.
- Play requires an **App Bundle** (`.aab`), not an APK. Keep that build path
  viable.
